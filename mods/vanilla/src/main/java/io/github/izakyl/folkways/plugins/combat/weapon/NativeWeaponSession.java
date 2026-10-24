package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.mixin.ProjectileWeaponInvoker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;

final class NativeWeaponSession implements WeaponSession {
    enum Kind { MELEE, BOW, CROSSBOW }

    private final Mob body;
    private final ResidentEntity resident;
    private final Optional<UUID> colony;
    private final WeaponAdapter adapter;
    private final Kind kind;
    private final boolean barehanded;
    private State state = State.READY;
    private LivingEntity target;
    private int elapsed;
    private long lastTick = Long.MIN_VALUE;
    private LivingEntity explicitTarget;
    private java.util.function.BooleanSupplier explicitPermission;

    NativeWeaponSession(Worker worker, WeaponAdapter adapter, Kind kind) {
        this(worker, adapter, kind, false);
    }

    private NativeWeaponSession(Worker worker, WeaponAdapter adapter, Kind kind, boolean barehanded) {
        body = worker.body();
        resident = body instanceof ResidentEntity person ? person : null;
        colony = resident == null ? Optional.empty() : resident.colonyId();
        this.adapter = adapter;
        this.kind = kind;
        this.barehanded = barehanded;
    }

    static WeaponSession barehanded(Worker worker) {
        return new NativeWeaponSession(worker, Weapons.MELEE, Kind.MELEE, true);
    }

    @Override
    public State state() { return state; }

    @Override
    public boolean explicitTarget(LivingEntity foe, java.util.function.BooleanSupplier authorized) {
        if (state != State.READY || explicitTarget != null || !(foe instanceof Mob) || !valid()
                || authorized == null || !authorized.getAsBoolean()) { return false; }
        explicitTarget = foe;
        explicitPermission = authorized;
        return true;
    }

    @Override
    public boolean fire(LivingEntity foe) {
        if (state != State.READY || !valid() || !permitted(foe)) {
            return false;
        }
        target = foe;
        elapsed = 0;
        if (kind == Kind.CROSSBOW && !CrossbowItem.isCharged(weapon())) {
            if (!beginReload()) {
                target = null;
                return false;
            }
        } else {
            if (kind == Kind.BOW && body.getProjectile(weapon()).isEmpty()) {
                target = null;
                return false;
            }
            state = State.FIRING;
            if (kind == Kind.BOW) {
                body.startUsingItem(InteractionHand.MAIN_HAND);
            }
        }
        return true;
    }

    @Override
    public boolean reload() {
        if (state != State.READY || kind != Kind.CROSSBOW || !valid()
                || CrossbowItem.isCharged(weapon())) {
            return false;
        }
        target = null;
        return beginReload();
    }

    private boolean beginReload() {
        if (body.getProjectile(weapon()).isEmpty()) {
            return false;
        }
        elapsed = 0;
        state = State.RELOADING;
        body.startUsingItem(InteractionHand.MAIN_HAND);
        return true;
    }

    @Override
    public void tick() {
        if (state == State.CLOSED) {
            return;
        }
        if (!valid()) {
            close();
            return;
        }
        if (lastTick == body.level().getGameTime()) {
            return;
        }
        lastTick = body.level().getGameTime();
        if (target != null && !permitted(target)) {
            stop();
            return;
        }
        if (state != State.FIRING && state != State.RELOADING) {
            return;
        }
        if (target != null) {
            body.getLookControl().setLookAt(target);
        }
        elapsed++;
        if (state == State.RELOADING) {
            int charge = CrossbowItem.getChargeDuration(weapon(), body);
            if (elapsed < charge) {
                return;
            }
            // Native release consumes the real projectile and writes CHARGED_PROJECTILES, including enchantments.
            ItemStack actual = weapon();
            actual.releaseUsing(body.level(), body, actual.getUseDuration(body) - elapsed);
            body.stopUsingItem();
            if (state == State.CLOSED || state == State.STOPPING) { return; }
            elapsed = 0;
            state = CrossbowItem.isCharged(actual) && target != null ? State.FIRING : State.READY;
            if (state == State.READY) {
                target = null;
            }
            return;
        }
        int duration = switch (kind) {
            case MELEE -> 10;
            case BOW -> BowItem.MAX_DRAW_DURATION;
            case CROSSBOW -> 1;
        };
        if (elapsed < duration || target == null) {
            return;
        }
        // The permission check is repeated immediately before the native action.
        if (permitted(target) && body.level() instanceof ServerLevel level) {
            ItemStack actual = weapon();
            switch (kind) {
                case MELEE -> {
                    body.swing(InteractionHand.MAIN_HAND);
                    if (body.doHurtTarget(target) && !actual.isEmpty()
                            && actual.getItem().hurtEnemy(actual, target, body)) {
                        actual.getItem().postHurtEnemy(actual, target, body);
                    }
                }
                case BOW -> {
                    aim(target);
                    List<ItemStack> projectiles = ProjectileWeaponInvoker.folkways$draw(actual, body.getProjectile(actual), body);
                    if (!projectiles.isEmpty()) {
                        ((ProjectileWeaponInvoker) actual.getItem()).folkways$shoot(level, body,
                            InteractionHand.MAIN_HAND, actual, projectiles, BowItem.getPowerForTime(elapsed) * 3,
                            1, elapsed >= BowItem.MAX_DRAW_DURATION, target);
                    }
                }
                case CROSSBOW -> ((CrossbowItem) actual.getItem()).performShooting(level, body,
                    InteractionHand.MAIN_HAND, actual, CrossbowItem.MOB_ARROW_POWER, 1, target);
            }
        }
        finish();
    }

    private void finish() {
        target = null;
        elapsed = 0;
        body.stopUsingItem();
        // Native durability breakage can close the hand lease during shoot(). Keep terminal states terminal.
        if (state != State.CLOSED && state != State.STOPPING) { state = State.READY; }
    }

    private void aim(LivingEntity foe) {
        double x = foe.getX() - body.getX();
        double z = foe.getZ() - body.getZ();
        double horizontal = Math.sqrt(x * x + z * z);
        double y = foe.getY(1.0 / 3) - body.getEyeY() + horizontal * 0.2;
        body.setYRot((float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90);
        body.setXRot((float) -(Mth.atan2(y, horizontal) * Mth.RAD_TO_DEG));
        body.setYHeadRot(body.getYRot());
    }

    private boolean permitted(LivingEntity foe) {
        return foe instanceof Mob mob && foe.level() == body.level() && foe.isAlive() && !foe.isRemoved()
            && (explicitTarget == null ? Threats.hostile(mob, body)
                : explicitTarget == foe && !mob.isAlliedTo(body) && !body.isAlliedTo(mob)
                    && io.github.izakyl.folkways.core.api.resident.body.Bodies.of(mob).isEmpty()
                    && explicitPermission.getAsBoolean()) && body.hasLineOfSight(foe)
            && body.distanceTo(foe) <= adapter.range() + (kind == Kind.MELEE ? foe.getBbWidth() : 0);
    }

    private boolean valid() {
        return body.level() instanceof ServerLevel level && level.getServer().isSameThread() && body.isAlive() && !body.isRemoved()
            && (resident == null || resident.colonyId().equals(colony))
            && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && (barehanded || resident != null && resident.ownsWeaponHand(this) && adapter.accepts(weapon()));
    }

    private ItemStack weapon() {
        return resident != null && resident.ownsWeaponHand(this) ? body.getMainHandItem() : ItemStack.EMPTY;
    }

    @Override
    public void stop() {
        if (state == State.CLOSED) {
            return;
        }
        state = State.STOPPING;
        explicitTarget = null;
        explicitPermission = null;
        target = null;
        elapsed = 0;
        // stopUsingItem clears use state; releaseUsingItem would discharge a fully drawn bow.
        body.stopUsingItem();
    }

    @Override
    public void close() {
        if (state == State.CLOSED) {
            return;
        }
        stop();
        state = State.CLOSED;
        if (resident != null) {
            resident.releaseWeaponHand(this);
        }
    }
}
