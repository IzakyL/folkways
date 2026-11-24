package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A Superb Warfare emplacement's aim as a signal, `(yaw, pitch)` in Minecraft rotation degrees. A gun position is a
 * set of controls on one gun, all written from one place and leased together on the gun itself, wherever it stands:
 * <ul>
 *   <li>a turret is written from its gunner's seat: {@code aim}, the {@link WarfareTrigger fire} pulse, and the
 *       {@link WarfareAmmo ammo} port over the gun's own container, which its native reload feeds from;</li>
 *   <li>a mortar is written from beside it: {@code aim}, and the {@link WarfareBreech breech} port, which fires what it
 *       is given by the mortar's own working.</li>
 * </ul>
 * The gun handles the shot after a write.
 */
public record WarfareMount(VehicleEntity vehicle) implements Signal {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "warfare/mounts");
    public static final String AIM = "aim";
    public static final SignalType TYPE = new SignalType.Vector(List.of(
        new SignalType.Real(-360, 360, 0), new SignalType.Real(-90, 90, 0)));

    /** The fixed guns among Superb Warfare vehicles: artillery and defence turrets with a fixed engine, and the FH-77. */
    public static boolean emplacement(VehicleEntity vehicle) {
        return vehicle instanceof Fh77bwEntity
            || vehicle.computed().getEngineType() == EngineType.FIXED
            && (vehicle.getVehicleType() == VehicleType.ARTILLERY
            || vehicle.getVehicleType() == VehicleType.DEFENSE);
    }

    /** The gun the mount fires: a mortar's only tube, otherwise the turret controller's weapon. */
    public static String weapon(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity) {
            return "Main";
        }
        return vehicle.getGunName(vehicle.getTurretControllerIndex());
    }

    /** The emplacements' signals in this cell: each gun's aim, and a turret's trigger. */
    static List<Signal> at(ServerLevel level, WorldPos cell) {
        List<Signal> found = new ArrayList<>();
        for (VehicleEntity vehicle : standing(level, cell)) {
            found.add(new WarfareMount(vehicle));
            if (!(vehicle instanceof MortarEntity)) {
                found.add(new WarfareTrigger(vehicle));
            }
        }
        return found;
    }

    static List<VehicleEntity> standing(ServerLevel level, WorldPos cell) {
        if (!cell.in(level)) {
            return List.of();
        }
        return level.getEntitiesOfClass(VehicleEntity.class, new AABB(cell.block(level)).inflate(2),
            vehicle -> emplacement(vehicle) && WorldSpaces.at(vehicle).equals(cell));
    }

    /** The seat a turret is written from: its gunner's; empty for a mortar, written from beside it. */
    public static Optional<Stances.Seat> seat(VehicleEntity vehicle) {
        return vehicle instanceof MortarEntity ? Optional.empty()
            : Optional.of(new Stances.Seat(vehicle.getUUID(), vehicle.getTurretControllerIndex()));
    }

    /** Where every control of the gun position is written from. */
    static Optional<Stances> stance(ServerLevel level, VehicleEntity vehicle) {
        Optional<Stances.Seat> seat = seat(vehicle);
        return seat.isPresent() ? seat.map(Stances.class::cast) : Control.reach(level, WorldSpaces.at(vehicle));
    }

    /**
     * Whole and unlocked, with no one aboard but whoever holds the gun or is sitting down at it to
     * take it.
     */
    static boolean valid(ServerLevel level, VehicleEntity vehicle, Control held) {
        Mob holder = Lease.held(held).map(lease -> lease.worker().body()).orElse(null);
        return vehicle.level() == level
            && level.getEntity(vehicle.getUUID()) == vehicle
            && !vehicle.isRemoved()
            && !vehicle.isWreck()
            && !vehicle.getLocked()
            && vehicle.getPassengers().stream().allMatch(passenger -> passenger == holder
                || passenger instanceof Mob mob && seat(vehicle).filter(seat -> Seating.holds(mob, seat)).isPresent());
    }

    /** Whether the writer is where the gun is written from: in the gunner's seat, or anywhere for a mortar. */
    static boolean manned(VehicleEntity vehicle, Mob writer) {
        return vehicle instanceof MortarEntity
            || writer.getVehicle() == vehicle && vehicle.getSeatIndex(writer) == vehicle.getTurretControllerIndex();
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(vehicle.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(vehicle);
    }

    @Override
    public String part() {
        // Several guns may stand in one cell; each is its own part.
        return AIM + "/" + vehicle.getUUID();
    }

    @Override
    public Object leaseKey() {
        return vehicle.getUUID();
    }

    @Override
    public SignalType type() {
        return TYPE;
    }

    @Override
    public boolean valid(ServerLevel level) {
        return valid(level, vehicle, this);
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return stance(level, vehicle);
    }

    /** Taken only by a writer already in the gunner's seat. */
    @Override
    public boolean enter(Mob writer) {
        return manned(vehicle, writer);
    }

    /** Lays the gun and turns the writer to face along it. */
    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        if (!valid(level) || !manned(vehicle, writer) || !(value instanceof Value.Vector(List<Double> angles))
            || angles.size() != 2) {
            return Write.REFUSED;
        }
        double yaw = angles.get(0);
        double pitch = angles.get(1);
        writer.setYRot((float) yaw);
        writer.setYHeadRot((float) yaw);
        writer.setXRot((float) pitch);
        if (vehicle instanceof MortarEntity mortar) {
            mortar.getEntityData().set(MortarEntity.TARGET_YAW, (float) yaw);
            mortar.getEntityData().set(MortarEntity.TARGET_PITCH, (float) pitch);
            return Write.WRITTEN;
        }
        Vec3 direction = direction(yaw, pitch);
        if (vehicle instanceof ArtilleryEntity artillery) {
            artillery.setLockTurret(false);
            artillery.setShootVec(direction.toVector3f());
        }
        vehicle.turretAutoAimFromVector(direction);
        return Write.WRITTEN;
    }

    /** The gun's muzzle, aim limits, shell and flight, for laying it from a script. */
    @Override
    public java.util.Map<String, Reading> readings() {
        return java.util.Map.of("ballistics", level -> valid(level) ? WarfareBallistics.read(vehicle)
            : Optional.empty());
    }

    @Override
    public Optional<Reading> feedback() {
        return Optional.of(level -> valid(level) ? Optional.of(aim((vehicle instanceof MortarEntity
            ? vehicle.getLookAngle() : vehicle.getShootVec(weapon(vehicle), 1)).normalize())) : Optional.empty());
    }

    /** The aim that points along a unit direction. */
    public static Value aim(Vec3 direction) {
        return Value.of(net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90),
            -Math.toDegrees(Math.asin(direction.y)));
    }

    private static Vec3 direction(double yaw, double pitch) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }
}
