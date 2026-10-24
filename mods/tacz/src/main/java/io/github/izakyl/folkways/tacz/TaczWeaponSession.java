package io.github.izakyl.folkways.tacz;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IGun;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

final class TaczWeaponSession implements WeaponSession {
    private final Worker worker;
    private final ResidentEntity body;
    private final Optional<UUID> colony;
    private final TaczWeaponAdapter adapter;
    private final IGunOperator operator;
    private State state = State.READY;
    private LivingEntity target;
    private long lastTick = Long.MIN_VALUE;

    TaczWeaponSession(Worker worker, TaczWeaponAdapter adapter) {
        this.worker = worker;
        body = (ResidentEntity) worker.body();
        colony = body.colonyId();
        this.adapter = adapter;
        operator = IGunOperator.fromLivingEntity(body);
    }
    void draw() {
        var data = operator.getDataHolder();
        long shot = data.shootTimestamp;
        long lastShot = data.lastShootTimestamp;
        // TACZ draw resets shootTimestamp. Handing the same gun back must not bypass its cooldown.
        operator.draw(this::weapon);
        data.shootTimestamp = shot;
        data.lastShootTimestamp = lastShot;
        TaczDischargeGuard.claim(body, this);
    }
    @Override public State state() { return state; }
    @Override public boolean continuousEngagement() { return true; }
    @Override public boolean fire(LivingEntity target) {
        if (state != State.READY || !valid() || !permitted(target)) { return false; }
        this.target = target;
        state = State.FIRING;
        operator.aim(true);
        return true;
    }
    @Override public boolean reload() {
        if (state != State.READY || !valid() || !TaczWeaponAdapter.hasAmmo(worker, weapon())) { return false; }
        operator.reload();
        if (!operator.getDataHolder().reloadStateType.isReloading()) { return false; }
        state = State.RELOADING;
        return true;
    }
    @Override public void tick() {
        if (state == State.CLOSED || state == State.STOPPING) { return; }
        if (!valid()) { close(); return; }
        if (lastTick == body.level().getGameTime()) { return; }
        lastTick = body.level().getGameTime();
        if (target != null && !permitted(target)) { stop(); return; }
        if (operator.getDataHolder().reloadStateType.isReloading()) { state = State.RELOADING; return; }
        if (target == null) { state = State.READY; return; }
        state = State.FIRING;
        aim();
        ShootResult result = operator.shoot(body::getXRot, body::getYRot);
        switch (result) {
            case NO_AMMO -> {
                if (!TaczWeaponAdapter.hasAmmo(worker, weapon())) { stop(); return; }
                operator.reload();
                if (operator.getDataHolder().reloadStateType.isReloading()) { state = State.RELOADING; }
            }
            case NEED_BOLT -> operator.bolt();
            case SUCCESS, COOL_DOWN, IS_DRAWING, IS_RELOADING, IS_BOLTING, IS_MELEE, IS_SPRINTING, OVERHEATED -> { }
            default -> stop();
        }
    }
    private void aim() {
        double x = target.getX() - body.getX();
        double z = target.getZ() - body.getZ();
        double y = target.getY(0.5) - body.getEyeY();
        body.setYRot((float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90);
        body.setXRot((float) -(Mth.atan2(y, Math.sqrt(x * x + z * z)) * Mth.RAD_TO_DEG));
        body.setYHeadRot(body.getYRot());
        body.getLookControl().setLookAt(target);
    }
    boolean discharge(ItemStack stack, LivingEntity intent) {
        return (state == State.FIRING || state == State.RELOADING) && target == intent && target != null
            && weapon() == stack && valid() && permitted(target);
    }
    LivingEntity target() { return target; }
    private boolean valid() {
        return body.level() instanceof ServerLevel level && level.getServer().isSameThread() && body.isAlive() && !body.isRemoved()
            && body.colonyId().equals(colony) && colony.filter(id -> Colonies.razed(level.getServer(), id)).isEmpty()
            && body.ownsWeaponHand(this) && adapter.accepts(weapon()) && TaczGuns.safeToDischarge(body, weapon());
    }
    private boolean permitted(LivingEntity foe) {
        ItemStack gun = weapon();
        return foe instanceof Mob mob && foe.level() == body.level() && foe.isAlive() && !foe.isRemoved()
            && Threats.hostile(mob, body) && body.hasLineOfSight(foe) && gun.getItem() instanceof IGun item
            && body.distanceTo(foe) <= TaczConfig.range(item.getGunId(gun)) && TaczDischargeGuard.clearLine(body, foe);
    }
    private ItemStack weapon() { return body.ownsWeaponHand(this) ? body.getMainHandItem() : ItemStack.EMPTY; }
    @Override public void stop() {
        if (state == State.CLOSED || state == State.STOPPING) { return; }
        state = State.STOPPING;
        TaczDischargeGuard.release(body, this);
        target = null;
        operator.cancelReload();
        operator.aim(false);
        var data = operator.getDataHolder();
        long shot = data.shootTimestamp;
        long lastShot = data.lastShootTimestamp;
        data.initialData();
        data.shootTimestamp = shot;
        data.lastShootTimestamp = lastShot;
        data.currentGunItem = null;
        body.stopUsingItem();
    }
    @Override public void close() {
        if (state == State.CLOSED) { return; }
        stop();
        state = State.CLOSED;
        body.releaseWeaponHand(this);
    }
}
