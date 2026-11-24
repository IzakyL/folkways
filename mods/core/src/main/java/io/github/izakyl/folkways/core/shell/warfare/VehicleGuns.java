package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.ShotPlanning;
import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** The sole aim owner and scoped native emission permit for mobile seat weapons. */
public final class VehicleGuns {
    private static final Map<VehicleEntity, VehicleGuns> ACTIVE = new HashMap<>();
    private final VehicleEntity vehicle;
    private final Map<Integer, Shot> aiming = new HashMap<>();
    private final Map<String, Long> nextShot = new HashMap<>();
    private Shot scheduling;
    private Shot emitting;
    private String diagnostic = "idle";
    private String lastQueue = "none";

    private record Shot(Mob operator, int seat, String weapon, Entity target, Vec3 direction,
                        BooleanSupplier authorized) {
    }

    private VehicleGuns(VehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    public static VehicleGuns of(VehicleEntity vehicle) {
        return ACTIVE.computeIfAbsent(vehicle, VehicleGuns::new);
    }

    static void remove(Entity entity) {
        ACTIVE.remove(entity);
    }

    static Runnable queued(VehicleEntity vehicle, Runnable nativeShot) {
        VehicleGuns guns = ACTIVE.get(vehicle);
        if (guns == null || guns.scheduling == null) {
            return nativeShot;
        }
        Shot shot = guns.scheduling;
        return () -> {
            if (!guns.valid(shot)) {
                guns.lastQueue = "lease expired seat " + shot.seat();
                return;
            }
            aim(shot);
            if (!guns.aligned(shot)) {
                guns.lastQueue = "seat " + shot.seat() + ": " + guns.diagnostic;
                return;
            }
            guns.lastQueue = "native seat " + shot.seat();
            guns.emitting = shot;
            try {
                nativeShot.run();
            } finally {
                guns.emitting = null;
            }
        };
    }

    static boolean permits(VehicleEntity vehicle, ShootParameters parameters) {
        VehicleGuns guns = ACTIVE.get(vehicle);
        return guns != null && guns.emitting != null && guns.emitting.operator() == parameters.shooter
            && guns.valid(guns.emitting);
    }

    public static Optional<Vec3> direction(VehicleEntity vehicle, String weapon, float partial) {
        VehicleGuns guns = ACTIVE.get(vehicle);
        Shot shot = guns == null ? null : guns.emitting != null ? guns.emitting : guns.scheduling;
        return shot != null && shot.weapon().equals(weapon) && guns.valid(shot)
            ? Optional.of(vehicle.getShootVec(shot.operator(), partial)) : Optional.empty();
    }

    public boolean canAim(int seat, Entity target) {
        Entity operator = vehicle.getNthEntity(seat);
        if (operator == null) {
            return false;
        }
        if (seat == vehicle.getTurretControllerIndex()
            || vehicle.hasPassengerWeaponStation() && seat == vehicle.getPassengerWeaponStationControllerIndex()) {
            return true;
        }
        Vec3 forward = vehicle.getTransformDirection(1, operator);
        Vec3 direction = target.getBoundingBox().getCenter().subtract(vehicle.getShootPos(operator, 1));
        double yaw = Mth.wrapDegrees(Math.toDegrees(Math.atan2(direction.z, direction.x)
            - Math.atan2(forward.z, forward.x)));
        var info = vehicle.getSeat(seat);
        return yaw >= info.getMinYaw() && yaw <= info.getMaxYaw();
    }

    public boolean planned(ServerLevel level, Colony colony, int seat, Entity target) {
        String weapon = vehicle.getGunName(seat);
        var data = vehicle.getGunData(weapon);
        Vec3 at = target.getBoundingBox().getCenter();
        Vec3 initial = data == null ? null : direction(seat, weapon, at);
        if (initial == null) {
            return false;
        }
        Vec3 from = vehicle.getShootPos(weapon, 1);
        double gravity = vehicle.getProjectileGravity(weapon);
        double speed = vehicle.getProjectileVelocity(weapon);
        Vec3 velocity = initial.scale(speed);
        List<Vec3> path = new ArrayList<>();
        path.add(from);
        for (int tick = 0; tick < Math.min(1200, data.get(GunProp.PROJECTILE_LIFE)); tick++) {
            Vec3 next = from.add(velocity);
            var hit = target.getBoundingBox().inflate(.2).clip(from, next);
            Vec3 end = hit.orElse(next);
            var block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER,
                ClipContext.Fluid.NONE, vehicle));
            path.add(block.getLocation());
            if (hit.isPresent() || block.getType() != HitResult.Type.MISS) {
                double spread = Math.sqrt(3) * .0172275 * Math.max(0, data.get(GunProp.SPREAD));
                return ShotPlanning.avoidsColony(level, colony, vehicle, path, .3, spread,
                    2 * data.get(GunProp.EXPLOSION_RADIUS));
            }
            from = next;
            velocity = velocity.add(0, -gravity, 0);
        }
        return false;
    }

    private Vec3 direction(int seat, String weapon, Vec3 at) {
        Vec3 from = vehicle.getShootPos(weapon, 1);
        double gravity = vehicle.getProjectileGravity(weapon);
        // SW sorts flight times descending; its named flat helper can return the high arc first.
        var seatInfo = vehicle.getSeat(seat);
        return gravity <= 0 ? at.subtract(from).normalize()
            : TrajectoryCalculator.INSTANCE.calculateShootVectors(from, at,
                vehicle.getProjectileVelocity(weapon), gravity).stream()
                .filter(candidate -> Math.toDegrees(Math.asin(candidate.y)) >= seatInfo.getMinPitch()
                    && Math.toDegrees(Math.asin(candidate.y)) <= seatInfo.getMaxPitch())
                .min(Comparator.comparingDouble(candidate -> Math.abs(candidate.y))).orElse(null);
    }

    public boolean engage(int seat, Mob body, Entity target, BooleanSupplier authorized) {
        if (!(body instanceof WeaponHands) || !target.isAlive()) {
            return false;
        }
        body.setTarget(null);
        String weapon = vehicle.getGunName(seat);
        var data = vehicle.getGunData(weapon);
        if (data == null) {
            return false;
        }
        Vec3 at = target.getBoundingBox().getCenter();
        Vec3 direction = direction(seat, weapon, at);
        if (direction == null) {
            diagnostic = "no firing solution";
            return false;
        }
        Shot shot = new Shot(body, seat, weapon, target, direction, authorized);
        if (!valid(shot)) {
            diagnostic = "invalid lease";
            return false;
        }
        aiming.put(seat, shot);
        track();
        if (!data.canShoot(vehicle)
            || vehicle.level().getGameTime() < nextShot.getOrDefault(seat + "/" + weapon, 0L)) {
            return false;
        }
        if (!aligned(shot)) {
            return false;
        }
        diagnostic = "queued";
        scheduling = shot;
        try {
            vehicle.vehicleShoot(body, weapon, target.getUUID(), at);
            nextShot.put(seat + "/" + weapon, vehicle.level().getGameTime()
                + Math.max(data.get(GunProp.SHOOT_DELAY_TIME) + 1,
                    (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
            return true;
        } finally {
            scheduling = null;
        }
    }

    public void clearAim() {
        aiming.clear();
        vehicle.setAiTurretTargetUUID("");
        vehicle.setAiPassengerWeaponTargetUUID("");
    }

    void track() {
        aiming.values().removeIf(shot -> !valid(shot));
        vehicle.setAiTurretTargetUUID("");
        vehicle.setAiPassengerWeaponTargetUUID("");
        for (Shot shot : aiming.values()) {
            shot.operator().setTarget(null);
            aim(shot);
            if (vehicle.hasPassengerWeaponStation()
                && shot.seat() == vehicle.getPassengerWeaponStationControllerIndex()) {
                vehicle.setAiPassengerWeaponTargetUUID(shot.target().getStringUUID());
            } else if (shot.seat() == vehicle.getTurretControllerIndex()) {
                vehicle.setAiTurretTargetUUID(shot.target().getStringUUID());
            }
        }
    }

    private static void aim(Shot shot) {
        Vec3 direction = shot.direction();
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
        shot.operator().setYRot(yaw);
        shot.operator().setYHeadRot(yaw);
        shot.operator().setXRot((float) -Math.toDegrees(Math.asin(direction.y)));
    }

    private boolean valid(Shot shot) {
        return shot.authorized().getAsBoolean() && vehicle.level() instanceof ServerLevel level
            && level.getServer().isSameThread()
            && level.getEntity(vehicle.getUUID()) == vehicle
            && VehicleSeats.occupant(vehicle, shot.seat()).filter(body -> body == shot.operator()).isPresent()
            && vehicle.getSeatIndex(shot.operator()) == shot.seat()
            && vehicle.getGunName(shot.seat()).equals(shot.weapon())
            && shot.target().level() == level && level.getEntity(shot.target().getUUID()) == shot.target()
            && shot.target().isAlive();
    }

    private boolean aligned(Shot shot) {
        Vec3 actual = vehicle.getShootVec(shot.operator(), 1).normalize();
        if (actual.dot(shot.direction()) < Math.cos(Math.toRadians(4))) {
            diagnostic = "alignment " + actual.dot(shot.direction());
            return false;
        }
        return true;
    }

    public String diagnostic() {
        return diagnostic + "; queue=" + lastQueue;
    }
}
