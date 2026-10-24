package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.data.gun.GunData;
import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.TowEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.tools.TrajectoryCalculator;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.plugins.combat.Threats;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Comparator;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

final class StationNode implements Node {
    private final Colony colony;
    private final ResidentEntity body;
    private final VehicleEntity station;
    private final ResidentCommands.Order order;
    private final NodeSpec spec;
    private final String weapon;
    private Mob target;
    private Vec3 direction;
    private long nextShot;
    private long lastCommit = Long.MIN_VALUE;
    private boolean claimed;
    private boolean closed;

    StationNode(Colony colony, ResidentEntity body, VehicleEntity station, ResidentCommands.Order order, Stances stances) {
        this.colony = colony; this.body = body; this.station = station; this.order = order;
        weapon = WarfareStations.weaponName(station);
        Workload.Continuous operate = (level, who) -> {
            if (!valid()) { return false; }
            // Each native action stays inside this node's commit, including delayed-shot preparation.
            commit(level, who);
            return !closed;
        };
        spec = NodeSpec.of(UUID.randomUUID(), WarfareStations.ID, WorkSite.on(station), stances, operate)
            .vocation(Vocations.required(PatrolContent.VOCATION)).doing(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "warfare_operating")).done();
    }

    Colony colony() { return colony; }
    ResidentEntity body() { return body; }
    VehicleEntity station() { return station; }
    public NodeSpec spec() { return spec; }
    public boolean directTravel() { return true; }
    public boolean ready(ServerLevel level) { return valid(); }

    boolean valid() {
        return !closed && body.level() instanceof ServerLevel level && body.isAlive() && !body.isRemoved()
            && station.level() == level && !station.isRemoved() && !station.isWreck() && !station.getLocked()
            && level.isLoaded(station.blockPosition()) && level.getEntity(body.getUUID()) == body
            && body.colonyId().filter(colony.id()::equals).isPresent() && colony.holdingOf(body.id()).isPresent()
            && body.licences().of(Vocations.required(PatrolContent.VOCATION)).isPresent()
            && body.getHealth() > body.getMaxHealth() * .35F
            && ResidentCommands.of(colony).current(body.id()).orElse(null) == order
            && WarfareStations.find(colony, body).orElse(null) == station
            && (!claimed || station instanceof MortarEntity || body.getVehicle() == station);
    }

    @Override public Outcome commit(ServerLevel level, Worker who) {
        if (!valid() || who.body() != body || who.pack() != body.pack()) { close(); return Outcome.done(); }
        if (lastCommit == level.getGameTime()) { return Outcome.done(); }
        lastCommit = level.getGameTime();
        if (!claimed) {
            if (body.distanceToSqr(station) > 5 * 5 || !WarfareStations.claim(station, this)) { close(); return Outcome.done(); }
            claimed = true;
            if (!(station instanceof MortarEntity)) {
                if (!order.mount(station)) { close(); return Outcome.done(); }
                int seat = station.getTurretControllerIndex();
                if (station.getSeatIndex(body) != seat && !station.changeSeat(body, seat)) { close(); return Outcome.done(); }
            }
        }
        // SBW's independent mob auto-fire uses Mob.target. The duty owns all fire intents instead.
        body.setTarget(null);
        order.engaging();
        GunData data = station.getGunData(weapon);
        if (data == null) { close(); return Outcome.done(); }
        replenish(data);
        if (station instanceof MortarEntity mortar && mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0) {
            if (!safe()) { mortar.getEntityData().set(MortarEntity.FIRE_TIME, 0); }
            return Outcome.done();
        }
        double range = data.get(GunProp.RANGE);
        target = level.getEntitiesOfClass(Mob.class, new AABB(station.position(), station.position()).inflate(range),
                foe -> Threats.hostile(foe, body) && station.distanceToSqr(foe) <= range * range
                    && WorldSpaces.at(foe).sameRealm(WorldSpaces.at(station)))
            .stream().sorted(Comparator.comparingDouble(station::distanceToSqr)).filter(foe -> aim(foe, data)).findFirst().orElse(null);
        if (target == null || direction == null) { return Outcome.done(); }
        aimStation();
        if (!safe() || level.getGameTime() < nextShot || !aligned()) { return Outcome.done(); }
        if (station instanceof MortarEntity mortar) {
            if (mortar.getItem(0).isEmpty()) { return Outcome.done(); }
            mortar.vehicleShoot(body, weapon, target.getBoundingBox().getCenter());
        } else if (station.canShoot(body)) {
            // The passenger overload preserves TOW's reload and backblast behavior.
            station.vehicleShoot(body, target.getUUID(), target.getBoundingBox().getCenter());
        } else { return Outcome.done(); }
        nextShot = level.getGameTime() + Math.max(1, (int) Math.ceil(1200.0 / Math.max(1, data.get(GunProp.RPM))));
        return Outcome.done();
    }

    private void replenish(GunData data) {
        ItemSpec ammo = WarfareStations.ammunition(station);
        if (ammo == null) { return; }
        if (station instanceof MortarEntity mortar) {
            if (!mortar.getItem(0).isEmpty() || mortar.getEntityData().get(MortarEntity.FIRE_TIME) > 0) { return; }
            ItemStack shell = Containers.extract(body.pack(), stack -> Goods.matches(stack, ammo), 1);
            // Mortar.setChanged starts a shot by itself. Stage the real shell, then explicitly authorize its fire.
            if (!shell.isEmpty()) { mortar.getItems().set(0, shell); }
        } else {
            if (data.hasEnoughAmmoToShoot(station.getAmmoSupplier()) || data.reloading()) { return; }
            if (station instanceof TowEntity tow && tow.getReloadCooldown() > 0) { return; }
            // TOW has no cargo slots; its interaction reloads directly from the operator.
            if (station instanceof TowEntity tow) {
                if (data.countBackupAmmoItem(body) > 0) {
                    station.modifyGunData(weapon, gun -> { gun.cachedBackupAmmo = -1; gun.reloadAmmo(body); });
                    tow.setLoaded(data.ammo.get() > 0);
                }
                return;
            }
            ItemStack round = Containers.extract(body.pack(), stack -> Goods.matches(stack, ammo), 1);
            if (round.isEmpty()) { return; }
            ItemStack remaining = net.neoforged.neoforge.items.ItemHandlerHelper.insertItemStacked(station.getInventory(), round, false);
            if (!remaining.isEmpty()) { body.pack().insert(remaining); }
            if (data.shouldStartReloading(station.getAmmoSupplier())) { data.startReload(); }
        }
    }

    private boolean aim(Mob foe, GunData data) {
        Vec3 from = muzzle();
        Vec3 at = foe.getBoundingBox().getCenter();
        // Submunitions and incendiary clouds have no bounded single impact footprint.
        if (data.get(GunProp.SHELL_TYPE).equals("CM") || data.get(GunProp.SHELL_TYPE).equals("WP")
                || station instanceof MortarEntity mortar && mortar.getItem(0).is(com.atsuishio.superbwarfare.init.ModItems.MORTAR_SHELL_WP.get())) { return false; }
        double gravity = station.getProjectileGravity(weapon);
        double velocity = station.getProjectileVelocity(weapon);
        for (boolean depressed : new boolean[] {true, false}) {
            Vec3 candidate = gravity <= .00001 ? at.subtract(from).normalize()
                : TrajectoryCalculator.calculateLaunchVector(from, at, velocity, gravity, depressed);
            if (candidate == null) { continue; }
            double pitch = Math.toDegrees(Math.asin(candidate.y));
            if (pitch < station.getTurretMinPitch() || pitch > station.getTurretMaxPitch()) { continue; }
            direction = candidate;
            return true;
        }
        direction = null;
        return false;
    }

    private Vec3 muzzle() { return station instanceof MortarEntity ? station.getEyePosition() : station.getShootPos(weapon, 1); }

    private void aimStation() {
        float yaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90);
        float pitch = (float) -Math.toDegrees(Math.asin(direction.y));
        body.setYRot(yaw); body.setYHeadRot(yaw); body.setXRot(pitch);
        if (station instanceof MortarEntity mortar) {
            mortar.getEntityData().set(MortarEntity.TARGET_YAW, yaw);
            mortar.getEntityData().set(MortarEntity.TARGET_PITCH, pitch);
        } else {
            if (station instanceof ArtilleryEntity artillery) { artillery.setLockTurret(false); artillery.setShootVec(direction.toVector3f()); }
            station.turretAutoAimFromVector(direction);
        }
    }

    private boolean aligned() {
        Vec3 actual = station instanceof MortarEntity ? station.getLookAngle() : station.getShootVec(weapon, 1);
        return actual.normalize().dot(direction) > .999999;
    }

    boolean safe() {
        if (target == null || direction == null || !(body.level() instanceof ServerLevel level)
                || !Threats.hostile(target, body) || !aligned()) { return false; }
        GunData data = station.getGunData(weapon);
        if (data == null) { return false; }
        double radius = Math.max(0, data.get(GunProp.EXPLOSION_RADIUS)) + 2;
        Vec3 from = muzzle();
        Vec3 actual = (station instanceof MortarEntity ? station.getLookAngle() : station.getShootVec(weapon, 1)).normalize();
        if (station instanceof TowEntity && !WarfareSafety.clear(level, body,
                from.subtract(actual.scale(1)), from.subtract(actual.scale(8)), 0)) { return false; }
        double speed = station.getProjectileVelocity(weapon);
        double gravity = station.getProjectileGravity(weapon);
        Vec3 velocity = actual.scale(speed);
        Vec3 at = from;
        // SBW adds bounded triangular noise on all three axes before scaling by muzzle velocity.
        double spreadVelocity = speed * Math.sqrt(3) * .0172275 * Math.max(0, data.get(GunProp.SPREAD));
        boolean reached = false;
        for (int tick = 0; tick < Math.min(1200, data.get(GunProp.PROJECTILE_LIFE)); tick++) {
            double spread = .3 + (tick + 1) * (spreadVelocity + speed * .002);
            Vec3 next = at.add(velocity);
            var hit = level.clip(new ClipContext(at, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, body));
            Vec3 end = hit.getType() == HitResult.Type.MISS ? next : hit.getLocation();
            // A physical impact ends this prediction; its whole blast margin must remain clear.
            if (!WarfareSafety.segment(level, body, at, end.subtract(end.subtract(at).normalize().scale(.01)), spread)) { return false; }
            if (target.getBoundingBox().inflate(.2).clip(at, end).isPresent()) {
                reached = true;
                if (!WarfareSafety.impact(level, body, target.getBoundingBox().getCenter(), radius + spread)) { return false; }
            }
            if (hit.getType() != HitResult.Type.MISS) {
                return (reached || end.distanceToSqr(target.position()) < (radius + 3) * (radius + 3))
                    && WarfareSafety.impact(level, body, end, radius + spread);
            }
            if (station instanceof TowEntity && at.distanceToSqr(target.position()) < speed * speed) {
                return WarfareSafety.impact(level, body, target.getBoundingBox().getCenter(), radius + spread);
            }
            at = next;
            velocity = velocity.add(0, -gravity, 0);
            if (at.y < level.getMinBuildHeight() || at.distanceToSqr(station.position()) > 2048 * 2048) { return false; }
        }
        return false;
    }

    public void released(ServerLevel level, Worker who, Ending how) { close(); }
    void close() {
        if (closed) { return; }
        closed = true;
        if (claimed) {
            if (station instanceof MortarEntity mortar) { mortar.getEntityData().set(MortarEntity.FIRE_TIME, 0); }
            order.dismount();
            body.setTarget(null);
            WarfareStations.release(station, this);
        }
    }
}
