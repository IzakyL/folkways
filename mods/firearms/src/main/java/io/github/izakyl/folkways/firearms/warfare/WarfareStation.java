package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class WarfareStation implements WeaponStations.Station {

    private final VehicleEntity station;
    private final String weapon;
    private ResidentEntity body;
    private Vec3 direction;
    private SpottedTargets.Spot observation;
    private Pending pending;
    private boolean emitting;
    private boolean scheduling;

    private record Pending(SpottedTargets.Spot spot, WeaponStations.Discharge discharge) {

    }

    WarfareStation(VehicleEntity station) {
        this.station = station;
        weapon = WarfareStations.weaponName(station);
    }

    public WorldPos at() {
        return WorldSpaces.at(station);
    }

    public Optional<UUID> entity() {
        return Optional.of(station.getUUID());
    }

    public double range() {
        GunData data = station.getGunData(weapon);
        return data == null ? 0 : data.get(GunProp.RANGE);
    }

    public Optional<Vec3> origin(ServerLevel level) {
        return Optional.of(muzzle());
    }

    public boolean indirect() {
        return station instanceof MortarEntity || station instanceof Fh77bwEntity;
    }

    public ItemSpec ammunition() {
        return WarfareStations.ammunition(station);
    }

    public int cycleTicks() {
        GunData data = station.getGunData(weapon);
        return Math.max(
            data.get(GunProp.BOLT_ACTION_TIME) + 1,
            Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
    }

    public boolean valid(ServerLevel level) {
        return station.level() == level
            && level.getEntity(station.getUUID()) == station
            && !station.isRemoved()
            && !station.isWreck()
            && !station.getLocked()
            && station.getDeltaMovement().horizontalDistanceSqr() < .001
            && WarfareStations.automatic(station)
            && ammunition() != null
            && station.getPassengers().stream().allMatch(passenger -> passenger == body);
    }

    public boolean operate(Worker operator, SpottedTargets.Spot observation) {
        return false;
    }

    public boolean enter(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident)) {
            return false;
        }
        body = resident;
        body.setTarget(null);
        if (!(station instanceof MortarEntity)) {
            if (!WeaponStations.mount(worker, station)) {
                return false;
            }
            int seat = station.getTurretControllerIndex();
            if (station.getSeatIndex(body) != seat && !station.changeSeat(body, seat)) {
                return false;
            }
        }
        return true;
    }

    public WeaponStations.Shot engage(Worker worker, SpottedTargets.Spot spot, WeaponStations.Discharge discharge) {
        if (body != worker.body()) {
            return WeaponStations.Shot.UNAVAILABLE;
        }
        body.setTarget(null);
        if (pending != null) {
            if (!pending.spot().target().equals(spot.target())) {
                cancel();
            } else {
                return WeaponStations.Shot.COOLDOWN;
            }
        }
        observation = spot;
        GunData data = station.getGunData(weapon);
        if (!aim(spot.position(), data)) {
            return WeaponStations.Shot.BLOCKED;
        }
        aimStation();
        if (!aligned()) {
            return WeaponStations.Shot.COOLDOWN;
        }
        if (!safe(worker, spot)) {
            return WeaponStations.Shot.BLOCKED;
        }
        ItemStack shell = worker.pack().getItem(0);
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            if (Goods.matches(worker.pack().getItem(slot), ammunition())) {
                shell = worker.pack().getItem(slot).copyWithCount(1);
                break;
            }
        }
        pending = new Pending(spot, discharge);
        if (station instanceof MortarEntity mortar) {
            if (!mortar.getItem(0).isEmpty() || mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0) {
                pending = null;
                return WeaponStations.Shot.UNAVAILABLE;
            }
            mortar.getItems().set(0, shell);
            try {
                mortar.vehicleShoot(body, weapon, spot.position());
                mortar.getPersistentData().putBoolean(WarfareStations.PENDING, true);
            } finally {
                mortar.getItems().set(0, ItemStack.EMPTY);
            }
        } else {
            scheduling = true;
            try {
                station.vehicleShoot(body, weapon, spot.target(), spot.position());
            } finally {
                scheduling = false;
            }
        }
        return WeaponStations.Shot.COOLDOWN;
    }

    public boolean safe(Worker worker, SpottedTargets.Spot spot) {
        return body == worker.body()
            && observation == spot
            && valid((ServerLevel) body.level())
            && WarfareExplosions.mayFire(body.level(), body)
            && trajectorySafe();
    }

    Runnable queued(Runnable nativeShot) {
        if (!scheduling) {
            return nativeShot;
        }
        Pending shot = pending;
        return () -> {
            if (shot == null || pending != shot) {
                return;
            }
            try {
                shot.discharge()
                    .fire(
                    () -> {
                        int before = station.getGunData(weapon).ammo.get();
                        station.modifyGunData(weapon, data -> data.ammo.set(before + 1));
                        emitting = true;
                        try {
                            return WarfareWeapon.receipt(nativeShot);
                        } finally {
                            emitting = false;
                            station.modifyGunData(weapon, data -> data.ammo.set(before));
                        }
                    });
            } finally {
                if (pending == shot) {
                    pending = null;
                }
            }
        };
    }

    boolean emitting() {
        return emitting;
    }

    void tickMortar(Runnable nativeTick) {
        MortarEntity mortar = (MortarEntity) station;
        if (mortar.getEntityData().get(MortarEntity.FIRE_TIME) != 6) {
            nativeTick.run();
            return;
        }
        Pending shot = pending;
        if (shot == null) {
            if (mortar.getPersistentData().getBoolean(WarfareStations.PENDING)) {
                cancel();
            }
            nativeTick.run();
            return;
        }
        try {
            WeaponStations.Shot result =
                shot.discharge()
                .fire(
                round -> {
                    mortar.getItems().set(0, round.copy());
                    try {
                        return WarfareWeapon.receipt(nativeTick);
                    } finally {
                        mortar.getItems().set(0, ItemStack.EMPTY);
                    }
                });
            if (result != WeaponStations.Shot.FIRED) {
                cancel();
                nativeTick.run();
            }
        } finally {
            if (pending == shot) {
                pending = null;
            }
            mortar.getPersistentData().remove(WarfareStations.PENDING);
        }
    }

    void cancel() {
        boolean owned = pending != null || station.getPersistentData().getBoolean(WarfareStations.PENDING);
        pending = null;
        if (owned && station instanceof MortarEntity mortar) {
            mortar.getEntityData().set(MortarEntity.FIRE_TIME, 0);
            mortar.getPersistentData().remove(WarfareStations.PENDING);
        }
    }

    public void release(Worker worker) {
        cancel();
        if (body != null) {
            body.setTarget(null);
        }
        body = null;
        observation = null;
    }

    private boolean aim(Vec3 observed, GunData data) {
        Vec3 from = muzzle();
        Vec3 at = observed.add(0, .9, 0);
        double gravity = station.getProjectileGravity(weapon);
        double velocity = station.getProjectileVelocity(weapon);
        for (boolean depressed : new boolean[] {true, false}) {
            Vec3 candidate =
                gravity <= .00001
                ? at.subtract(from).normalize()
                : TrajectoryCalculator.calculateLaunchVector(from, at, velocity, gravity, depressed);
            if (candidate == null) {
                continue;
            }
            double pitch = Math.toDegrees(Math.asin(candidate.y));
            if (pitch < station.getTurretMinPitch() || pitch > station.getTurretMaxPitch()) {
                continue;
            }
            direction = candidate;
            return true;
        }
        direction = null;
        return false;
    }

    private Vec3 muzzle() {
        return station instanceof MortarEntity ? station.getEyePosition() : station.getShootPos(weapon, 1);
    }

    private void aimStation() {
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.asin(direction.y));
        body.setYRot(yaw);
        body.setYHeadRot(yaw);
        body.setXRot(pitch);
        if (station instanceof MortarEntity mortar) {
            mortar.getEntityData().set(MortarEntity.TARGET_YAW, yaw);
            mortar.getEntityData().set(MortarEntity.TARGET_PITCH, pitch);
        } else {
            if (station instanceof ArtilleryEntity artillery) {
                artillery.setLockTurret(false);
                artillery.setShootVec(direction.toVector3f());
            }
            station.turretAutoAimFromVector(direction);
        }
    }

    private boolean aligned() {
        Vec3 actual = station instanceof MortarEntity ? station.getLookAngle() : station.getShootVec(weapon, 1);
        return actual.normalize().dot(direction) > Math.cos(Math.toRadians(1));
    }

    private boolean trajectorySafe() {
        if (observation == null || direction == null || !(body.level() instanceof ServerLevel level) || !aligned()) {
            return false;
        }
        GunData data = station.getGunData(weapon);
        if (data == null) {
            return false;
        }
        double radius = Math.max(0, data.get(GunProp.EXPLOSION_RADIUS));
        double entityRadius = radius * 2;
        // SBW's block search is flattened and bounded by 0.6 times its configured radius.
        double blockRadius = radius * .6;
        Vec3 from = muzzle();
        Vec3 actual =
            (station instanceof MortarEntity ? station.getLookAngle() : station.getShootVec(weapon, 1)).normalize();
        double speed = station.getProjectileVelocity(weapon);
        double gravity = station.getProjectileGravity(weapon);
        Vec3 velocity = actual.scale(speed);
        Vec3 at = from;
        // SBW adds bounded triangular noise on all three axes before scaling by muzzle velocity.
        double spreadVelocity = speed * Math.sqrt(3) * .0172275 * Math.max(0, data.get(GunProp.SPREAD));
        AABB targetBox = new AABB(observation.position(), observation.position().add(0, 1.8, 0)).inflate(.3);
        boolean reached = false;
        for (int tick = 0; tick < Math.min(1200, data.get(GunProp.PROJECTILE_LIFE)); tick++) {
            double spread = .3 + (tick + 1) * (spreadVelocity + speed * .002);
            Vec3 next = at.add(velocity);
            var hit = level.clip(new ClipContext(at, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, body));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
            // A physical impact ends this prediction; its whole blast margin must remain clear.
            if (!WarfareSafety.segment(
                level, body, at, end.subtract(end.subtract(at).normalize().scale(.01)), spread)) {
                return false;
            }
            if (targetBox.inflate(.2).clip(at, end).isPresent()) {
                reached = true;
                if (!WarfareSafety.impact(
                    level, body, targetBox.getCenter(), entityRadius + spread, blockRadius + spread)) {
                    return false;
                }
            }
            if (hit.getType() != HitResult.Type.MISS) {
                boolean okay =
                    (reached || end.distanceToSqr(observation.position()) < (entityRadius + 3) * (entityRadius + 3))
                    && WarfareSafety.impact(level, body, end, entityRadius + spread, blockRadius + spread);
                return okay;
            }
            at = next;
            velocity = velocity.add(0, -gravity, 0);
            if (at.y < level.getMinBuildHeight() || at.distanceToSqr(station.position()) > 2048 * 2048) {
                return false;
            }
        }
        return false;
    }
}
