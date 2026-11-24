package io.github.izakyl.folkways.core.shell.epicfight;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.Loadouts;
import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.shell.epicfight.api.EpicFightCombatant;
import io.github.izakyl.folkways.core.shell.epicfight.api.EpicFightStrike;
import io.github.izakyl.folkways.core.work.api.Worker;
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
public final class EpicFightWeaponAttack implements EpicFightStrike {
    private record Hit(UUID target, int phase) { }

    private final Mob body;
    private final WeaponHands hands;
    private final Worker worker;
    private final EpicFightCombatant patch;
    private final Optional<UUID> colony;
    private final BooleanSupplier engagement;
    private final Set<Hit> tried = new HashSet<>();
    private State state = State.READY;
    private LivingEntity target;
    private AssetAccessor<? extends StaticAnimation> animation;
    private long started;
    private long lastTick = Long.MIN_VALUE;
    private LivingEntity explicitTarget;
    private BooleanSupplier explicitPermission;
    private BooleanSupplier lease = () -> true;

    EpicFightWeaponAttack(Worker worker, WeaponHands hands, EpicFightCombatant patch) {
        this.worker = worker;
        this.hands = hands;
        this.body = hands.mob();
        this.patch = patch;
        this.colony = hands.colonyId();
        this.engagement = patch.engagementLease();
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public boolean continuousEngagement() {
        return true;
    }

    @Override
    public boolean explicitTarget(LivingEntity foe, BooleanSupplier authorized) {
        if (state != State.READY || explicitTarget != null || !(foe instanceof Mob) || !valid()
                || authorized == null || !authorized.getAsBoolean()) { return false; }
        explicitTarget = foe;
        explicitPermission = authorized;
        return true;
    }

    @Override
    public void leasedBy(BooleanSupplier lease) {
        this.lease = java.util.Objects.requireNonNull(lease);
    }

    @Override
    public boolean fire(LivingEntity foe) {
        if (state != State.READY || !valid() || !permitted(foe) || patch.blocksLabor()) {
            return false;
        }
        target = foe;
        tried.clear();
        started = body.level().getGameTime();
        state = State.FIRING;
        face(foe);
        body.getNavigation().stop();
        try {
            return beginStrike();
        } catch (RuntimeException | LinkageError failure) {
            stop();
            throw failure;
        }
    }

    private boolean beginStrike() {
        if (!valid() || !permitted(target)) {
            stop();
            return false;
        }
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
    public boolean reload() {
        return false;
    }

    @Override
    public void tick() {
        if (state == State.STOPPED) {
            return;
        }
        if (!valid()) {
            stop();
            return;
        }
        long now = body.level().getGameTime();
        if (lastTick == now) {
            return;
        }
        lastTick = now;
        if (state != State.FIRING) {
            return;
        }
        beforeAnimationTick();
        if (state != State.FIRING) {
            return;
        }
        if (!patch.ownsStrike(this) || animation == null || !patch.getAnimator().isPlaying(animation)) {
            patch.endStrike(this, false);
            target = null;
            animation = null;
            state = State.READY;
        }
    }

    /** EF advances before the ordinary entity tick, so revoke a stale attack before that advance too. */
    public void beforeAnimationTick() {
        // Revoke before native frames advance; the work node handles physical holstering.
        if (state != State.STOPPED && !valid()) {
            stop();
            return;
        }
        if (state == State.FIRING && (!permitted(target) || patch.isStunned()
                || body.level().getGameTime() - started > 400)) {
            stop();
        }
    }

    public LivingEntity target() {
        return target;
    }

    public boolean authorized() {
        return state == State.FIRING && valid() && permitted(target);
    }

    public boolean playing() {
        return animation != null && patch.getAnimator().isPlaying(animation);
    }

    public boolean permits(LivingEntity victim) {
        // A sweep may pass through other mobs; only the target the owning node admitted can be struck.
        return authorized() && victim == target && !patch.isStunned() && permitted(victim);
    }

    public boolean admitHit(EpicFightDamageSource source, LivingEntity victim) {
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

    public void spentHit(LivingEntity victim) {
        // Mob.doHurtTarget already applies damage/enchantments. Only the real item durability remains here.
        ItemStack weapon = body.getMainHandItem();
        if (!weapon.isEmpty() && weapon.getItem().hurtEnemy(weapon, victim, body)) {
            weapon.getItem().postHurtEnemy(weapon, victim, body);
        }
    }

    private void face(LivingEntity foe) {
        double x = foe.getX() - body.getX();
        double z = foe.getZ() - body.getZ();
        float yaw = (float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90;
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.yBodyRot = yaw;
        body.getLookControl().setLookAt(foe);
    }

    private boolean permitted(LivingEntity foe) {
        return lease.getAsBoolean() && foe instanceof Mob mob && foe.level() == body.level() && foe.isAlive() && !foe.isRemoved()
            && (explicitTarget == null ? patch.hostile(mob)
                : explicitTarget == foe && !mob.isAlliedTo(body) && !body.isAlliedTo(mob)
                    && Bodies.of(mob).isEmpty()
                    && explicitPermission.getAsBoolean())
            && body.hasLineOfSight(foe)
            && body.distanceTo(foe) <= Loadouts.using(body.getMainHandItem())
                .map(provider -> provider.range(body.getMainHandItem()))
                .orElseGet(EpicFightWeaponAdapter.INSTANCE::range) + foe.getBbWidth();
    }

    private boolean valid() {
        return body.level() instanceof ServerLevel level && level.getServer().isSameThread()
            && body.isAlive() && !body.isRemoved() && !body.isPassenger()
            && hands.colonyId().equals(colony)
            && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && engagement.getAsBoolean()
            && worker.body() == body && worker.pack() == hands.pack() && worker.resident().id().equals(hands.id())
            && hands.isActiveAttack(this)
            && patch.mayEngage(worker) && !body.getMainHandItem().isEmpty()
            && EpicFightWeaponAdapter.INSTANCE.accepts(body.getMainHandItem());
    }

    @Override
    public void stop() {
        if (state == State.STOPPED) {
            return;
        }
        state = State.STOPPED;
        explicitTarget = null;
        explicitPermission = null;
        target = null;
        // Revoke before ending the animation: animation callbacks can run synchronously from this operation.
        patch.endStrike(this, true);
        animation = null;
        body.stopUsingItem();
        hands.endAttack(this);
    }

}
