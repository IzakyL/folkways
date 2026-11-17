package io.github.izakyl.folkways.firearms.warfare;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.combat.Threats;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

final class WarfareAttack implements WeaponAttack {

    private final Worker worker;
    private final ResidentEntity body;
    private final WarfareWeapon adapter;
    private final Optional<UUID> colony;
    private BooleanSupplier lease = () -> true;
    private State state = State.READY;
    private Entity target;
    private Entity observedTarget;
    private BooleanSupplier authorized;
    private long lastTick = Long.MIN_VALUE;

    WarfareAttack(Worker worker, WarfareWeapon adapter) {
        this.worker = worker;
        this.body = (ResidentEntity) worker.body();
        this.adapter = adapter;
        colony = body.colonyId();
    }

    @Override
    public void leasedBy(BooleanSupplier lease) {
        this.lease = Objects.requireNonNull(lease);
    }

    public State state() {
        return state;
    }

    public boolean continuousEngagement() {
        return true;
    }

    public boolean fire(LivingEntity target) {
        return fireEntity(target);
    }

    @Override
    public boolean fireEntity(Entity target) {
        if (state != State.READY || !valid() || !permitted(target) || !adapter.usable(worker, body.getMainHandItem())) {
            return false;
        }
        this.target = target;
        state = State.FIRING;
        return true;
    }

    public boolean reload() {
        return false;
    }

    public void tick() {
        if (state == State.STOPPED) {
            return;
        }
        if (!valid()) {
            stop();
            return;
        }
        ServerLevel level = (ServerLevel) body.level();
        if (lastTick == level.getGameTime()) {
            return;
        }
        lastTick = level.getGameTime();
        if (state != State.FIRING) {
            return;
        }
        if (!permitted(target) || !adapter.usable(worker, body.getMainHandItem())) {
            stop();
            return;
        }
        body.getLookControl().setLookAt(target, 90, 90);
        if (adapter.shoot(level, worker, target, body.getMainHandItem())) {
            target = null;
            state = State.READY;
        }
    }

    public boolean spottedTarget(LivingEntity target, Vec3 observed, BooleanSupplier authorized) {
        this.observedTarget = target;
        this.authorized = authorized;
        return valid() && permitted(target);
    }

    @Override
    public boolean explicitEntityTarget(Entity target, BooleanSupplier authorized) {
        this.observedTarget = target;
        this.authorized = authorized;
        return valid() && permitted(target);
    }

    @Override
    public boolean explicitTarget(LivingEntity target, BooleanSupplier authorized) {
        return explicitEntityTarget(target, authorized);
    }

    private boolean permitted(Entity target) {
        return lease.getAsBoolean() && target != null && target.isAlive()
            && target.level() == body.level()
            && (authorized != null && target == observedTarget && authorized.getAsBoolean()
                || target instanceof Mob mob && Threats.hostile(mob, body))
            && (authorized == null || target == observedTarget && authorized.getAsBoolean())
            && WeaponRules.permits(worker, target, body.getMainHandItem())
            && body.distanceToSqr(target) <= adapter.range() * adapter.range()
            && body.hasLineOfSight(target)
            && WarfareSafety.segment(
            (ServerLevel) body.level(),
            body,
            body.getEyePosition(),
            target.getBoundingBox().getCenter(),
            .65);
    }

    private boolean valid() {
        return body.level() instanceof ServerLevel level
            && level.getServer().isSameThread()
            && body.isAlive()
            && !body.isRemoved()
            && body.colonyId().equals(colony)
            && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && body.isActiveAttack(this)
            && adapter.accepts(body.getMainHandItem());
    }

    public void stop() {
        if (state != State.STOPPED) {
            target = null;
            state = State.STOPPED;
            body.endAttack(this);
        }
    }

}
