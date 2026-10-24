package io.github.izakyl.folkways.warfare;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

final class WarfareSession implements WeaponSession {
    private final Worker worker;
    private final ResidentEntity body;
    private final WarfareWeapon adapter;
    private final Optional<UUID> colony;
    private State state = State.READY;
    private LivingEntity target;
    private long lastTick = Long.MIN_VALUE;

    WarfareSession(Worker worker, WarfareWeapon adapter) {
        this.worker = worker;
        this.body = (ResidentEntity) worker.body();
        this.adapter = adapter;
        colony = body.colonyId();
    }
    public State state() { return state; }
    public boolean continuousEngagement() { return true; }
    public boolean fire(LivingEntity target) {
        if (state != State.READY || !valid() || !permitted(target)
                || Goods.countIn(worker.pack(), adapter.ammunition()) == 0) { return false; }
        this.target = target;
        state = State.FIRING;
        return true;
    }
    public boolean reload() { return false; }
    public void tick() {
        if (state == State.CLOSED) { return; }
        if (!valid()) { close(); return; }
        ServerLevel level = (ServerLevel) body.level();
        if (lastTick == level.getGameTime()) { return; }
        lastTick = level.getGameTime();
        if (state != State.FIRING) { return; }
        if (!permitted(target) || Goods.countIn(worker.pack(), adapter.ammunition()) == 0) { stop(); return; }
        body.getLookControl().setLookAt(target, 90, 90);
        if (adapter.shoot(level, worker, target, body.getMainHandItem())) { target = null; state = State.READY; }
    }
    private boolean permitted(LivingEntity target) {
        return target instanceof Mob mob && target.level() == body.level() && Threats.hostile(mob, body)
            && body.distanceToSqr(target) <= adapter.range() * adapter.range() && body.hasLineOfSight(target)
            && WarfareSafety.segment((ServerLevel) body.level(), body, body.getEyePosition(), target.getBoundingBox().getCenter(), .65);
    }
    private boolean valid() {
        return body.level() instanceof ServerLevel level && level.getServer().isSameThread()
            && body.isAlive() && !body.isRemoved() && body.colonyId().equals(colony)
            && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && body.ownsWeaponHand(this) && adapter.accepts(body.getMainHandItem());
    }
    public void stop() { if (state != State.CLOSED) { target = null; state = State.STOPPING; } }
    public void close() {
        if (state == State.CLOSED) { return; }
        stop(); state = State.CLOSED; body.releaseWeaponHand(this);
    }
}
