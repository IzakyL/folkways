package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.ShotPlanning;
import io.github.izakyl.folkways.core.combat.api.Trajectories;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.warfare.WarfareAmmo;
import io.github.izakyl.folkways.core.shell.warfare.WarfareBreech;
import io.github.izakyl.folkways.core.shell.warfare.WarfareMount;
import io.github.izakyl.folkways.core.shell.warfare.WarfareTrigger;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * A Superb Warfare emplacement as a sentry fires it: the core gun position, with range, cycle and aim solving. A turret
 * is fired by its trigger from the gun's own container, which the gunner tops up from its pack; a mortar by loading a
 * carried shell into its tube.
 */
final class WarfareStation implements WeaponStations.Station {

    private final VehicleEntity station;
    private final WarfareMount mount;
    private final String weapon;

    WarfareStation(VehicleEntity station) {
        this.station = station;
        mount = new WarfareMount(station);
        weapon = WarfareMount.weapon(station);
    }

    @Override
    public boolean planned(ServerLevel level, Colony colony,
                           Mob operator, Mob target) {
        var at = target.position();
        var data = station.getGunData(weapon);
        return data != null && solve(at).map(WarfareMount::aim)
            .flatMap(angles -> WarfareStations.launch(station, weapon, angles))
            .map(launch -> Trajectories.trace(level, launch, at))
            .filter(path -> path.endsAtMark())
            .filter(path -> ShotPlanning.avoidsColony(level, colony, station,
                path.points(), .35, 2 * data.get(GunProp.EXPLOSION_RADIUS))).isPresent();
    }

    @Override
    public WorldPos anchor() {
        return mount.anchor();
    }

    @Override
    public String part() {
        return mount.part();
    }

    @Override
    public Object leaseKey() {
        return mount.leaseKey();
    }

    @Override
    public SignalType type() {
        return mount.type();
    }

    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return mount.stance(level);
    }

    @Override
    public Write write(ServerLevel level, Mob writer, Value value) {
        return mount.write(level, writer, value);
    }

    @Override
    public double range() {
        GunData data = station.getGunData(weapon);
        return data == null ? 0 : data.get(GunProp.RANGE);
    }

    @Override
    public Optional<Vec3> origin(ServerLevel level) {
        return Optional.of(muzzle());
    }

    @Override
    public boolean indirect() {
        return station instanceof MortarEntity || station instanceof Fh77bwEntity;
    }

    @Override
    public ItemSpec ammunition() {
        return WarfareStations.ammunition(station);
    }

    @Override
    public int cycleTicks() {
        GunData data = station.getGunData(weapon);
        return Math.max(
            data.get(GunProp.BOLT_ACTION_TIME) + 1,
            Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM)))));
    }

    @Override
    public boolean loaded(ServerLevel level, Worker worker) {
        GunData data = station.getGunData(weapon);
        return data != null && data.ammo.get() >= data.get(GunProp.AMMO_COST_PER_SHOOT);
    }

    @Override
    public boolean valid(ServerLevel level) {
        return mount.valid(level) && WarfareStations.automatic(station) && ammunition() != null;
    }

    @Override
    public boolean admits(Mob operator) {
        return operator instanceof ResidentEntity;
    }

    @Override
    public boolean enter(Mob writer) {
        if (!(writer instanceof ResidentEntity) || !mount.enter(writer)) {
            return false;
        }
        writer.setTarget(null);
        return true;
    }

    @Override
    public void leave(Mob writer) {
        writer.setTarget(null);
    }

    @Override
    public WeaponStations.Laying lay(ServerLevel level, Mob operator, Vec3 at) {
        operator.setTarget(null);
        Optional<Vec3> direction = solve(at.add(0, .9, 0));
        if (direction.isEmpty()) {
            return WeaponStations.Laying.NO_SOLUTION;
        }
        if (mount.write(level, operator, WarfareMount.aim(direction.get())) != Write.WRITTEN) {
            return WeaponStations.Laying.REFUSED;
        }
        return aligned(direction.get()) ? WeaponStations.Laying.LAID : WeaponStations.Laying.TURNING;
    }

    @Override
    public Optional<Signal> trigger(ServerLevel level) {
        return station instanceof MortarEntity ? Optional.empty() : Optional.of(new WarfareTrigger(station));
    }

    @Override
    public Optional<Port<ItemStack>> magazine(ServerLevel level) {
        return station instanceof MortarEntity || station.getContainerSize() <= 0 ? Optional.empty()
            : Optional.of(new WarfareAmmo(station));
    }

    @Override
    public Optional<Port<ItemStack>> breech(ServerLevel level) {
        return station instanceof MortarEntity mortar ? Optional.of(new WarfareBreech(mortar)) : Optional.empty();
    }

    // The launch direction that reaches the observed position within the gun's pitch, preferring the flatter arc.
    Optional<Vec3> solve(Vec3 at) {
        Vec3 from = muzzle();
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
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private Vec3 muzzle() {
        return station instanceof MortarEntity ? station.getEyePosition() : station.getShootPos(weapon, 1);
    }

    private boolean aligned(Vec3 direction) {
        Vec3 actual = station instanceof MortarEntity ? station.getLookAngle() : station.getShootVec(weapon, 1);
        return actual.normalize().dot(direction) > Math.cos(Math.toRadians(1));
    }
}
