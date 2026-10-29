package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.DefenseControl;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutRegistry;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import yesman.epicfight.api.animation.AnimationPlayer;
import yesman.epicfight.api.animation.types.AttackAnimation;
import yesman.epicfight.api.animation.types.StaticAnimation;
import yesman.epicfight.api.asset.AssetAccessor;
import yesman.epicfight.world.damagesource.EpicFightDamageSource;

/** One strike's revocable permission. Only Epic Fight's real collision frames may spend it. */
public final class EpicFightWeaponSession implements WeaponSession {
    private record Hit(UUID target, int phase) { }

    private final ResidentEntity resident;
    private final Worker worker;
    private final ResidentEpicFightPatch patch;
    private final ItemStack loan;
    private final Optional<UUID> colony;
    private final DefenseControl.Order command;
    private final Set<Hit> tried = new HashSet<>();
    private State state = State.READY;
    private LivingEntity target;
    private AssetAccessor<? extends StaticAnimation> animation;
    private long started;
    private long lastTick = Long.MIN_VALUE;
    private LivingEntity explicitTarget;
    private BooleanSupplier explicitPermission;

    EpicFightWeaponSession(Worker worker, ResidentEntity resident, ResidentEpicFightPatch patch, ItemStack loan) {
        this.worker = worker;
        this.resident = resident;
        this.patch = patch;
        this.loan = loan;
        this.colony = resident.colonyId();
        this.command = resident.level() instanceof ServerLevel level
            ? colony.flatMap(id -> Colonies.of(level.getServer(), id)).map(DefenseControl::of)
                .flatMap(commands -> commands.current(resident.id())).orElse(null)
            : null;
    }

    @Override
    public State state() { return state; }

    @Override
    public boolean continuousEngagement() { return true; }

    @Override
    public boolean explicitTarget(LivingEntity foe, BooleanSupplier authorized) {
        if (state != State.READY || explicitTarget != null || !(foe instanceof Mob) || !valid()
                || authorized == null || !authorized.getAsBoolean()) { return false; }
        explicitTarget = foe;
        explicitPermission = authorized;
        return true;
    }

    @Override
    public boolean fire(LivingEntity foe) {
        if (state != State.READY || !valid() || !permitted(foe) || patch.blocksLabor()) {
            return false;
        }
        target = foe;
        tried.clear();
        started = resident.level().getGameTime();
        state = State.FIRING;
        face(foe);
        resident.getNavigation().stop();
        try {
            return beginStrike();
        } catch (RuntimeException | LinkageError failure) {
            close();
            throw failure;
        }
    }

    private boolean beginStrike() {
        if (!valid() || !permitted(target)) { stop(); return false; }
        if (!patch.beginStrike(this)) {
            target = null;
            state = State.READY;
            return false;
        }
        animation = patch.getAnimator().getPlayerFor(null).getRealAnimation();
        if (!(animation.get() instanceof AttackAnimation)) {
            stop();
            return false;
        }
        return true;
    }

    @Override
    public boolean reload() { return false; }

    @Override
    public void tick() {
        if (state == State.CLOSED) { return; }
        if (!valid()) {
            close();
            return;
        }
        long now = resident.level().getGameTime();
        if (lastTick == now) { return; }
        lastTick = now;
        if (state != State.FIRING) { return; }
        beforeAnimationTick();
        if (state != State.FIRING) { return; }
        if (!patch.ownsStrike(this) || animation == null || !patch.getAnimator().isPlaying(animation)) {
            patch.endStrike(this, false);
            target = null;
            animation = null;
            state = State.READY;
        }
    }

    /** EF advances before the ordinary entity tick, so revoke a stale loan before that advance too. */
    void beforeAnimationTick() {
        // Native animation advances before BodyRunner releases an interrupted job. Revoke and return its
        // physical hand here as well; a newly issued command must never inherit the old collision lease.
        if (state != State.CLOSED && !valid()) { close(); return; }
        if (state == State.FIRING && (!permitted(target) || patch.isStunned()
                || resident.level().getGameTime() - started > 400)) {
            stop();
        }
    }

    LivingEntity target() { return target; }

    boolean authorized() { return state == State.FIRING && valid() && permitted(target); }

    boolean playing() {
        return animation != null && patch.getAnimator().isPlaying(animation);
    }

    boolean permits(LivingEntity victim) {
        return authorized() && !patch.isStunned() && permitted(victim);
    }

    boolean admitHit(EpicFightDamageSource source, LivingEntity victim) {
        if (!permits(victim) || animation == null || source.getAnimation().get() != animation.get()
                || !patch.getAnimator().isPlaying(animation) || !(animation.get() instanceof AttackAnimation attack)) {
            return false;
        }
        AnimationPlayer player = patch.getAnimator().getPlayerFor(animation);
        float elapsed = player.getElapsedTime();
        AttackAnimation.Phase phase = attack.getPhaseByTime(elapsed);
        // Include a contact interval crossed between two ticks; never turn wind-up into an immediate hit.
        if (elapsed + 0.0001F < phase.preDelay || player.getPrevElapsedTime() > phase.contact + 0.0001F) {
            return false;
        }
        return tried.add(new Hit(victim.getUUID(), attack.getPhaseOrderByTime(elapsed)));
    }

    void spentHit(LivingEntity victim) {
        // Mob.doHurtTarget already applies damage/enchantments. Only the real item durability remains here.
        if (!loan.isEmpty() && loan.getItem().hurtEnemy(loan, victim, resident)) {
            loan.getItem().postHurtEnemy(loan, victim, resident);
        }
    }

    private void face(LivingEntity foe) {
        double x = foe.getX() - resident.getX();
        double z = foe.getZ() - resident.getZ();
        float yaw = (float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90;
        resident.setYRot(yaw);
        resident.setYHeadRot(yaw);
        resident.yBodyRot = yaw;
        resident.getLookControl().setLookAt(foe);
    }

    private boolean permitted(LivingEntity foe) {
        return foe instanceof Mob mob && foe.level() == resident.level() && foe.isAlive() && !foe.isRemoved()
            && (explicitTarget == null ? Threats.hostile(mob, resident)
                : explicitTarget == foe && !mob.isAlliedTo(resident) && !resident.isAlliedTo(mob)
                    && io.github.izakyl.folkways.core.api.resident.body.Bodies.of(mob).isEmpty()
                    && explicitPermission.getAsBoolean())
            && resident.hasLineOfSight(foe)
            && resident.distanceTo(foe) <= LoadoutRegistry.using(loan).map(provider -> provider.range(loan))
                .orElseGet(EpicFightWeaponAdapter.INSTANCE::range) + foe.getBbWidth();
    }

    private boolean valid() {
        return resident.level() instanceof ServerLevel level && level.getServer().isSameThread()
            && resident.isAlive() && !resident.isRemoved() && !resident.isPassenger()
            && resident.colonyId().equals(colony)
            && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && commandValid(level)
            && worker.body() == resident && worker.pack() == resident.pack() && worker.resident().id().equals(resident.id())
            && resident.ownsWeaponHand(this) && resident.getMainHandItem() == loan
            && Weapons.permitted(worker) && !loan.isEmpty() && EpicFightWeaponAdapter.INSTANCE.accepts(loan);
    }

    private boolean commandValid(ServerLevel level) {
        if (colony.isEmpty()) { return command == null; }
        var live = Colonies.of(level.getServer(), colony.get());
        if (live.isEmpty()) { return false; }
        DefenseControl.Order current = DefenseControl.of(live.get()).current(resident.id()).orElse(null);
        // No order is a real lease identity too: self-defense cannot survive a new MOVE/RETREAT.
        return current == command;
    }

    @Override
    public void stop() {
        if (state == State.CLOSED || state == State.STOPPING) { return; }
        state = State.STOPPING;
        explicitTarget = null;
        explicitPermission = null;
        target = null;
        // Revoke before ending the animation: animation callbacks can run synchronously from this operation.
        patch.endStrike(this, true);
        animation = null;
        resident.stopUsingItem();
    }

    @Override
    public void close() {
        if (state == State.CLOSED) { return; }
        try {
            stop();
        } finally {
            state = State.CLOSED;
            resident.releaseWeaponHand(this);
        }
    }
}
