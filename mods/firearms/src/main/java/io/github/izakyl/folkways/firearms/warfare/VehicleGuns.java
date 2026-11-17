package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.Mth;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** The sole aim owner and scoped native emission permit for mobile seat weapons. */
final class VehicleGuns {
    private static final Map<VehicleEntity, VehicleGuns> ACTIVE = new HashMap<>();
    private final VehicleEntity vehicle;
    private final Map<Integer, Shot> aiming = new HashMap<>();
    private final Map<String, Long> nextShot = new HashMap<>();
    private Shot scheduling;
    private Shot emitting;
    private String diagnostic = "idle";
    private String lastQueue = "none";

    private record Shot(ResidentEntity operator, int seat, String weapon, Entity target, Vec3 direction,
                        BooleanSupplier authorized) {
    }

    private VehicleGuns(VehicleEntity vehicle) {
        this.vehicle = vehicle;
    }

    static VehicleGuns of(VehicleEntity vehicle) {
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
            if (!guns.safe(shot)) {
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

    static Optional<Vec3> direction(VehicleEntity vehicle, String weapon, float partial) {
        VehicleGuns guns = ACTIVE.get(vehicle);
        Shot shot = guns == null ? null : guns.emitting != null ? guns.emitting : guns.scheduling;
        return shot != null && shot.weapon().equals(weapon) && guns.valid(shot)
            ? Optional.of(vehicle.getShootVec(shot.operator(), partial)) : Optional.empty();
    }

    boolean canAim(int seat, Entity target) {
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

    boolean engage(int seat, Entity target, BooleanSupplier authorized) {
        if (!(vehicle.getPassengers().stream().filter(entity -> vehicle.getSeatIndex(entity) == seat)
            .findFirst().orElse(null) instanceof ResidentEntity body) || !target.isAlive()) {
            return false;
        }
        body.setTarget(null);
        String weapon = vehicle.getGunName(seat);
        var data = vehicle.getGunData(weapon);
        if (data == null) {
            return false;
        }
        Vec3 from = vehicle.getShootPos(weapon, 1);
        Vec3 at = target.getBoundingBox().getCenter();
        double gravity = vehicle.getProjectileGravity(weapon);
        // SW sorts flight times descending; its named flat helper can return the high arc first.
        var seatInfo = vehicle.getSeat(seat);
        Vec3 direction = gravity <= 0 ? at.subtract(from).normalize()
            : TrajectoryCalculator.INSTANCE.calculateShootVectors(from, at,
                vehicle.getProjectileVelocity(weapon), gravity).stream()
                .filter(candidate -> Math.toDegrees(Math.asin(candidate.y)) >= seatInfo.getMinPitch()
                    && Math.toDegrees(Math.asin(candidate.y)) <= seatInfo.getMaxPitch())
                .min(Comparator.comparingDouble(candidate -> Math.abs(candidate.y))).orElse(null);
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
        if (!safe(shot)) {
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

    void clearAim() {
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
            && level.getEntity(vehicle.getUUID()) == vehicle && VehicleSeats.holds(vehicle, shot.operator())
            && vehicle.getSeatIndex(shot.operator()) == shot.seat()
            && vehicle.getGunName(shot.seat()).equals(shot.weapon())
            && shot.target().level() == level && level.getEntity(shot.target().getUUID()) == shot.target()
            && shot.target().isAlive() && !shot.target().isAlliedTo(shot.operator());
    }

    private boolean safe(Shot shot) {
        var data = vehicle.getGunData(shot.weapon());
        Vec3 actual = vehicle.getShootVec(shot.operator(), 1).normalize();
        if (data == null || !WarfareExplosions.mayFire(vehicle.level(), shot.operator())) {
            diagnostic = "griefing disabled";
            return false;
        }
        if (actual.dot(shot.direction()) < Math.cos(Math.toRadians(4))) {
            diagnostic = "alignment " + actual.dot(shot.direction());
            return false;
        }
        ServerLevel level = (ServerLevel) vehicle.level();
        Vec3 from = vehicle.getShootPos(shot.weapon(), 1);
        double speed = vehicle.getProjectileVelocity(shot.weapon());
        double gravity = vehicle.getProjectileGravity(shot.weapon());
        Vec3 velocity = actual.scale(speed);
        double spread = speed * Math.sqrt(3) * .0172275 * Math.max(0, data.get(GunProp.SPREAD));
        double blast = Math.max(0, data.get(GunProp.EXPLOSION_RADIUS));
        for (int tick = 0; tick < Math.min(1200, data.get(GunProp.PROJECTILE_LIFE)); tick++) {
            Vec3 next = from.add(velocity);
            var hit = shot.target().getBoundingBox().inflate(.2).clip(from, next);
            Vec3 end = hit.orElse(next);
            double margin = .3 + (tick + 1) * (spread + speed * .002);
            if (!WarfareSafety.segment(level, shot.operator(), from, end, margin)) {
                diagnostic = "unsafe segment " + tick;
                return false;
            }
            if (hit.isPresent()) {
                boolean safe = WarfareSafety.impact(level, shot.operator(), end,
                    blast * 2 + margin, blast * .6 + margin);
                diagnostic = safe ? "safe" : "unsafe impact";
                return safe;
            }
            from = next;
            velocity = velocity.add(0, -gravity, 0);
            if (from.distanceToSqr(vehicle.position()) > data.get(GunProp.RANGE) * data.get(GunProp.RANGE)) {
                return false;
            }
        }
        diagnostic = "trajectory misses target";
        return false;
    }

    String diagnostic() {
        return diagnostic + "; queue=" + lastQueue;
    }
}
