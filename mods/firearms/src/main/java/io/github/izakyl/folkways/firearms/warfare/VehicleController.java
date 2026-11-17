package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils;
import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Plans motion at the native engine boundary; VehicleEntity still owns collision and movement. */
final class VehicleController {
    private static final Map<VehicleEntity, VehicleController> ACTIVE = new HashMap<>();
    private final VehicleEntity vehicle;
    private final VehicleSeparation separation;
    private Vehicles.Order order;
    private List<Vec3> points = List.of();
    private int point;
    private Vec3 hold;
    private LandingSites.Site landing;
    private LandingSites.Site departure;
    private VehicleCatapultLaunch launch;
    private Vec3 orbit;
    private WorldSpaces.Frame dock;
    private UUID dockId;
    private Vec3 dockLocal;
    private Vec3 dockForward;
    private Vec3 dockUp;
    private boolean approachAligned;
    private boolean touchdown;
    private int groundContactTicks;
    private boolean departed;
    private boolean settle;
    private Vec3 liftedFrom;
    private String action = "STOP";
    private final Map<Integer, Vehicles.Firing> firing = new HashMap<>();
    private boolean decoy;
    private boolean sprint;
    private int idleTicks;

    private VehicleController(VehicleEntity vehicle) {
        this.vehicle = vehicle;
        separation = new VehicleSeparation(vehicle);
        hold = vehicle.position();
        order = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(), Optional.empty());
        restore();
    }

    static VehicleController of(VehicleEntity vehicle) {
        return ACTIVE.computeIfAbsent(vehicle, VehicleController::new);
    }

    static void remove(Entity entity) {
        var controller = ACTIVE.remove(entity);
        if (controller != null) {
            controller.save();
        }
        if (controller != null && controller.launch != null) {
            controller.launch.cancel();
        }
        if (entity instanceof VehicleEntity vehicle) {
            VehicleRunwayTraffic.release(vehicle);
        }
    }

    String action() {
        return "folkways.rts.unit.state." + action.toLowerCase(Locale.ROOT);
    }

    boolean idle() {
        return order.action() == Vehicles.Action.STOP;
    }

    Vehicles.Route route() {
        return order.points().isEmpty() ? Vehicles.Route.EMPTY
            : new Vehicles.Route(order.points(), points.isEmpty() ? -1 : point, order.cycleStart());
    }

    Optional<Vehicles.Target> target() {
        return order.target().map(id -> ((ServerLevel) vehicle.level()).getEntity(id))
            .filter(Entity::isAlive).map(Vehicles.Target::of);
    }

    List<Vehicles.Firing> firing() {
        return WarfareVehicles.INSTANCE.seats(vehicle).stream().filter(seat -> !seat.weapons().isEmpty())
            .map(seat -> firing.getOrDefault(seat.index(),
                new Vehicles.Firing(seat.index(), Optional.empty(), false))).toList();
    }

    Vehicles.Result append(WorldPos next, boolean attackMove) {
        Vehicles.Action mode = attackMove ? Vehicles.Action.ATTACK_MOVE : Vehicles.Action.MOVE;
        boolean extending = order.action() == mode && order.cycleStart() < 0;
        var route = new ArrayList<>(extending ? order.points() : List.of());
        if (route.size() >= Vehicles.MAX_POINTS) {
            return Vehicles.Result.refused("destination");
        }
        int current = extending ? points.isEmpty() ? route.size() : point : 0;
        route.add(next);
        var result = order(new Vehicles.Order(mode, route, -1, Optional.empty(), Optional.empty()));
        if (result.accepted()) {
            point = current;
            save();
        }
        return result;
    }

    Vehicles.Result cycle(int start) {
        if (start < 0 || start >= order.points().size() - 1 || order.cycleStart() >= 0) {
            return Vehicles.Result.refused("destination");
        }
        int current = points.isEmpty() ? start : point;
        var result = order(new Vehicles.Order(order.action(), order.points(), start,
            order.target(), order.airport()));
        if (result.accepted()) {
            point = current;
            save();
        }
        return result;
    }

    Vehicles.Result order(Vehicles.Order next) {
        boolean crew = vehicle.getPassengers().stream().filter(LivingEntity.class::isInstance)
            .map(LivingEntity.class::cast).anyMatch(body -> VehicleSeats.holds(vehicle, body));
        if (!crew) {
            return Vehicles.Result.refused("no_crew");
        }
        boolean movement = switch (next.action()) {
            case MOVE, PATROL, ATTACK_MOVE, SPRINT, TAKE_OFF, LAND, LOITER -> true;
            default -> false;
        };
        if (movement && (!(vehicle.getNthEntity(0) instanceof LivingEntity driver)
            || !VehicleSeats.holds(vehicle, driver))) {
            return Vehicles.Result.refused("no_driver");
        }
        if (!WarfareVehicles.INSTANCE.capabilities(vehicle).actions().contains(next.action())) {
            return Vehicles.Result.refused("unsupported");
        }
        if (next.action() == Vehicles.Action.DECOY) {
            idleTicks = 0;
            decoy = true;
            return Vehicles.Result.success();
        }
        if (next.action() == Vehicles.Action.SPRINT) {
            idleTicks = 0;
            sprint = !sprint;
            return Vehicles.Result.success();
        }
        ServerLevel level = (ServerLevel) vehicle.level();
        List<Vec3> route = next.points().stream().map(pos -> WorldSpaces.world(level, pos)).flatMap(Optional::stream)
            .toList();
        if (route.size() != next.points().size() || route.size() > Vehicles.MAX_POINTS
            || route.stream().anyMatch(pos -> !level.hasChunkAt(BlockPos.containing(pos)))) {
            return Vehicles.Result.refused("destination");
        }
        boolean moving = next.action() == Vehicles.Action.MOVE || next.action() == Vehicles.Action.PATROL
            || next.action() == Vehicles.Action.ATTACK_MOVE;
        if (moving && (route.isEmpty() || next.cycleStart() < -1 || next.cycleStart() >= route.size())) {
            return Vehicles.Result.refused("destination");
        }
        boolean aircraft = vehicle.getEngineInfo() instanceof EngineInfo.Helicopter;
        boolean airborne = VehicleAirports.airborne(vehicle);
        boolean parked = aircraft && !airborne && (dock != null || vehicle.onGround()
            || !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft) || onSurface());
        boolean alreadyDeparted = next.action() == Vehicles.Action.TAKE_OFF && airborne;
        // There is no Take off button: a flight order to a parked aircraft departs through its airport first.
        boolean automatic = parked && switch (next.action()) {
            case MOVE, PATROL, ATTACK_MOVE, LOITER -> true;
            default -> false;
        };
        boolean departs = next.action() == Vehicles.Action.TAKE_OFF && !alreadyDeparted || automatic;
        LandingSites.Site assigned = null;
        if (next.action() == Vehicles.Action.LAND || departs) {
            VehicleAirports.home(vehicle);
            VehicleRunwayTraffic.release(vehicle);
            assigned = VehicleAirports.assign(vehicle, departs, next.airport()).orElse(null);
            if (assigned == null && !(departs && vehicle.getEngineInfo() instanceof EngineInfo.Helicopter
                    && !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft))
                    && !(automatic && VehicleAirports.offField(vehicle))) {
                if (departure != null || landing != null) {
                    VehicleRunwayTraffic.acquire(vehicle, departure != null ? departure : landing, departure != null);
                }
                return Vehicles.Result.refused("landing_site");
            }
        } else {
            VehicleRunwayTraffic.release(vehicle);
        }
        if (dock != null) {
            vehicle.setDeltaMovement(dock.velocity(dockLocal));
            dock = null;
            dockId = null;
        }
        landing = next.action() == Vehicles.Action.LAND ? assigned : null;
        orbit = null;
        approachAligned = false;
        touchdown = false;
        departed = alreadyDeparted || (aircraft ? !parked && (airborne || departed)
            : !vehicle.onGround() && vehicle.getY() > hold.y + vehicle.getBbHeight());
        LandingSites.Site departing = departs ? assigned : null;
        if (launch != null) {
            launch.cancel();
        }
        departure = departing;
        launch = null;
        if (departure != null && !airborne && vehicle.getEngineInfo() instanceof EngineInfo.Aircraft) {
            var site = departure;
            site.catapults().stream().filter(catapult -> VehicleCatapults.requiredLength(vehicle, site, catapult)
                <= site.length()).min(Comparator.comparingDouble(catapult ->
                    VehicleCatapults.requiredLength(vehicle, site, catapult)))
                .ifPresent(catapult -> launch = new VehicleCatapultLaunch(vehicle, site, catapult));
        }
        if (departure != null) {
            VehicleRunwayTraffic.acquire(vehicle, departure);
        } else if (landing != null && landing.kind() == LandingSites.Kind.RUNWAY_END) {
            VehicleRunwayTraffic.acquire(vehicle, landing);
        }
        idleTicks = 0;
        separation.clear();
        if (moving && next.cycleStart() < 0 && !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft)) {
            separation.reserve(route.getLast(), ACTIVE.values().stream().map(value -> value.separation).toList());
        }
        VehicleGuns.of(vehicle).clearAim();
        order = next;
        points = route;
        point = 0;
        hold = vehicle.position();
        settle = next.action() == Vehicles.Action.STOP;
        liftedFrom = parked ? vehicle.position() : null;
        action = next.action().name();
        save();
        return Vehicles.Result.success();
    }

    void tick() {
        if (order == null) {
            return;
        }
        vehicle.setDecoyInputDown(decoy);
        decoy = false;
        fire();
        releaseIdleCrew();
        save();
    }

    private void releaseIdleCrew() {
        boolean idle = order.action() == Vehicles.Action.STOP
            || points.isEmpty() && action.equals("HOLD") && target().isEmpty();
        if (!vehicle.getPersistentData().getBoolean(Vehicles.RELEASE_IDLE) || !idle || !atRest(vehicle)
            || launch != null || landing != null || departure != null || action.equals("LOITER")) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks >= 200) {
            WarfareVehicles.INSTANCE.leave(vehicle, -1);
            idleTicks = 0;
        }
    }

    static boolean commanded(VehicleEntity vehicle) {
        return ACTIVE.containsKey(vehicle) && !vehicle.isWreck() && VehicleSeats.owner(vehicle).isPresent()
            && vehicle.getNthEntity(0) instanceof LivingEntity driver && VehicleSeats.holds(vehicle, driver)
            && vehicle.getPassengers().stream().allMatch(passenger -> passenger instanceof LivingEntity body
                && VehicleSeats.holds(vehicle, body));
    }

    static boolean drive(VehicleEntity vehicle, EngineInfo engine) {
        if (!(engine instanceof EngineInfo.Wheel || engine instanceof EngineInfo.Ship
            || engine instanceof EngineInfo.Helicopter || engine instanceof EngineInfo.AirShip)
            || vehicle.level().isClientSide() || !commanded(vehicle)) {
            return false;
        }
        ACTIVE.get(vehicle).move(engine);
        ACTIVE.get(vehicle).save();
        return true;
    }

    private void move(EngineInfo engine) {
        if (dockId != null) {
            dock = WorldSpaces.frame(vehicle.level(), dockId).orElse(null);
        }
        if (dock != null) {
            DirectVehicleMotion.controlled(vehicle, engine instanceof EngineInfo.Helicopter);
            if (WorldSpaces.frame(vehicle.level(), dock.id()).isEmpty()) {
                dock = null;
                landing = null;
                hold = vehicle.position();
            } else {
                Vec3 world = dock.toWorld(dockLocal);
                Vec3 forward = dock.toWorld(dockLocal.add(dockForward)).subtract(world).normalize();
                vehicle.setYRot((float) Math.toDegrees(Math.atan2(-forward.x, forward.z)));
                vehicle.setXRot((float) -Math.toDegrees(Math.asin(Mth.clamp(forward.y, -1, 1))));
                Vec3 up = dock.toWorld(dockLocal.add(dockUp)).subtract(world).normalize();
                double yaw = Math.toRadians(vehicle.getYRot());
                double pitch = Math.toRadians(vehicle.getXRot());
                Vec3 right = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
                Vec3 upright = new Vec3(-Math.sin(yaw) * Math.sin(pitch), Math.cos(pitch),
                    Math.cos(yaw) * Math.sin(pitch));
                vehicle.setZRot((float) Math.toDegrees(Math.atan2(-up.dot(right), up.dot(upright))));
                vehicle.setDeltaMovement(world.subtract(vehicle.position()).add(0, vehicle.computed().getGravity(), 0));
                vehicle.setPower(0);
                rotors();
                return;
            }
        }
        // Native work is also the authoritative failure model: no thrust or attitude planning without energy.
        int gate = engine instanceof EngineInfo.Helicopter && !(engine instanceof EngineInfo.Aircraft)
            ? (int) engine.getEnergyCostRate() : (int) (engine.getEnergyCostRate() * Math.abs(vehicle.getPower()));
        if (vehicle.getEnergy() <= gate || vehicle.getHealth() <= vehicle.getMaxHealth() * 0.1f) {
            DirectVehicleMotion.controlled(vehicle, false);
            engine.work(vehicle);
            return;
        }
        DirectVehicleMotion.controlled(vehicle, engine instanceof EngineInfo.Helicopter);
        Vec3 destination = separation.destination(points.isEmpty() ? hold : points.get(point));
        if (landing != null) {
            destination = landing.pose(vehicle.level()).map(LandingSites.Pose::position).orElse(hold);
        }
        if (engine instanceof EngineInfo.Aircraft aircraft) {
            aircraft(destination, aircraft);
        } else if (engine instanceof EngineInfo.Helicopter helicopter) {
            helicopter(destination, helicopter);
        } else if (engine instanceof EngineInfo.AirShip airship) {
            airship(destination, airship);
        } else {
            ground(destination, engine);
        }
        int cost = engine instanceof EngineInfo.Helicopter && !(engine instanceof EngineInfo.Aircraft)
            ? (int) ((int) engine.getEnergyCostRate() * 8.3333f * Math.abs(vehicle.getPower())) : gate;
        vehicle.consumeEnergy(cost);
    }

    private void fire() {
        VehicleGuns.of(vehicle).clearAim();
        firing.clear();
        ServerLevel level = (ServerLevel) vehicle.level();
        var rules = WarfareVehicles.rules(vehicle);
        Entity ordered = order.target().map(level::getEntity).orElse(null);
        Set<Entity> engaged = new HashSet<>();
        for (var seat : WarfareVehicles.INSTANCE.seats(vehicle)) {
            if (seat.weapons().isEmpty() || seat.occupant().isEmpty()) {
                continue;
            }
            String weapon = vehicle.getGunName(seat.index());
            var rule = rules.getOrDefault(seat.index() + "/" + weapon,
                WeaponRules.DEFAULT);
            var data = vehicle.getGunData(weapon);
            if (data == null) {
                continue;
            }
            var target = ordered;
            if (target == null && rule.mode() == WeaponRules.Mode.FREE) {
                var operator = vehicle.getNthEntity(seat.index());
                if (operator instanceof LivingEntity body) {
                    target = level.getEntitiesOfClass(Mob.class,
                        vehicle.getBoundingBox().inflate(data.get(GunProp.RANGE)),
                        foe -> Combatant.get().hostile(foe, body))
                        .stream().filter(foe -> rule.permits(false, vehicle.distanceTo(foe), false))
                        .filter(foe -> VehicleGuns.of(vehicle).canAim(seat.index(), foe))
                        .min(Comparator.<Mob, Boolean>comparing(engaged::contains)
                            .thenComparingDouble(vehicle::distanceToSqr)).orElse(null);
                }
            }
            if (target != null && rule.permits(target == ordered, vehicle.distanceTo(target), false)
                && vehicle.distanceTo(target) <= data.get(GunProp.RANGE)) {
                engaged.add(target);
                Vehicles.Order expected = order;
                Entity aimed = target;
                boolean explicitlyOrdered = target == ordered;
                boolean shooting = VehicleGuns.of(vehicle).engage(seat.index(), target, () -> order == expected
                    && WarfareVehicles.rules(vehicle)
                        .getOrDefault(seat.index() + "/" + weapon,
                            WeaponRules.DEFAULT)
                        .permits(explicitlyOrdered, vehicle.distanceTo(aimed), false));
                firing.put(seat.index(), new Vehicles.Firing(seat.index(),
                    Optional.of(Vehicles.Target.of(target)), shooting));
            }
        }
    }

    void cancel() {
        VehicleGuns.of(vehicle).clearAim();
        VehicleRunwayTraffic.release(vehicle);
        if (launch != null) {
            launch.cancel();
        }
        launch = null;
        departure = null;
        touchdown = false;
        approachAligned = false;
        separation.clear();
        idleTicks = 0;
        firing.clear();
        order = new Vehicles.Order(Vehicles.Action.STOP, List.of(), -1, Optional.empty(), Optional.empty());
        hold = vehicle.position();
        points = List.of();
        landing = null;
        orbit = null;
        action = "STOP";
        decoy = false;
        sprint = false;
        save();
    }

    static boolean atRest(VehicleEntity vehicle) {
        Vec3 velocity = vehicle.getDeltaMovement();
        var address = WorldSpaces.at(vehicle);
        if (address.realm() instanceof Realm.Frame frame) {
            var space = WorldSpaces.frame(vehicle.level(), frame.structure()).orElse(null);
            if (space != null) {
                velocity = velocity.subtract(space.velocity(space.toLocal(vehicle.position())));
            }
        }
        return velocity.horizontalDistanceSqr() <= .0001
            && Math.abs(velocity.y) <= vehicle.computed().getGravity() + .01
            && (vehicle.onGround() || of(vehicle).onSurface());
    }

    private void save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("dimension", vehicle.level().dimension().location().toString());
        tag.putString("command", order.action().name());
        ListTag route = new ListTag();
        order.points().forEach(pos -> route.add(pos.save()));
        tag.put("route", route);
        tag.putInt("cycle", order.cycleStart());
        tag.putInt("point", point);
        tag.putBoolean("finished", points.isEmpty());
        order.target().ifPresent(id -> tag.putUUID("target", id));
        order.airport().ifPresent(id -> tag.putString("airport", id));
        tag.putBoolean("departed", departed);
        tag.putBoolean("touchdown", touchdown);
        if (liftedFrom != null) {
            vector(tag, "liftedFrom", liftedFrom);
        }
        tag.putBoolean("sprint", sprint);
        tag.putString("action", action);
        vector(tag, "hold", hold);
        if (dockId != null) {
            tag.putUUID("dock", dockId);
            vector(tag, "dockLocal", dockLocal);
            vector(tag, "dockForward", dockForward);
            vector(tag, "dockUp", dockUp);
        }
        if (landing != null) {
            tag.putString("landing", landing.id());
        }
        if (departure != null) {
            tag.putString("departure", departure.id());
        }
        vehicle.getPersistentData().put("folkways_firearms:controller", tag);
    }

    private void restore() {
        CompoundTag tag = vehicle.getPersistentData().getCompound("folkways_firearms:controller");
        if (tag.isEmpty()) {
            return;
        }
        var route = new ArrayList<WorldPos>();
        for (var entry : tag.getList("route", 10)) {
            WorldPos.load(Reader.of((CompoundTag) entry)).ifPresent(route::add);
        }
        order = new Vehicles.Order(Vehicles.Action.valueOf(tag.getString("command")), route,
            tag.getInt("cycle"), tag.hasUUID("target") ? Optional.of(tag.getUUID("target")) : Optional.empty(),
            tag.contains("airport") ? Optional.of(tag.getString("airport")) : Optional.empty());
        points = tag.getBoolean("finished") ? List.of() : route.stream()
            .map(pos -> WorldSpaces.world(vehicle.level(), pos)).flatMap(Optional::stream).toList();
        point = Math.clamp(tag.getInt("point"), 0, Math.max(0, points.size() - 1));
        hold = tag.getString("dimension").equals(vehicle.level().dimension().location().toString())
            ? vector(tag, "hold") : vehicle.position();
        departed = tag.getBoolean("departed") || VehicleAirports.airborne(vehicle);
        sprint = tag.getBoolean("sprint");
        touchdown = tag.getBoolean("touchdown") && onSurface();
        liftedFrom = tag.contains("liftedFrom") ? vector(tag, "liftedFrom") : null;
        action = tag.getString("action");
        if (tag.hasUUID("dock")) {
            dockId = tag.getUUID("dock");
            dock = WorldSpaces.frame(vehicle.level(), dockId).orElse(null);
            dockLocal = vector(tag, "dockLocal");
            dockForward = vector(tag, "dockForward");
            dockUp = vector(tag, "dockUp");
        }
        for (var site : VehicleAirports.sites(vehicle)) {
            if (site.id().equals(tag.getString("landing"))) {
                landing = site;
            }
            if (site.id().equals(tag.getString("departure"))) {
                departure = site;
            }
        }
        // A launch cannot safely resume its native spring state; restart the approach or hold on the surface.
        if (tag.contains("departure") && !departed) {
            if (departure != null) {
                departure.catapults().forEach(rail ->
                    VehicleCatapults.trigger((ServerLevel) vehicle.level(), rail, false));
            }
            String towing = vehicle.getTowedByUUID();
            if (!towing.isEmpty()) {
                var tug = ((ServerLevel) vehicle.level()).getEntity(UUID.fromString(towing));
                if (tug instanceof com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity shuttle) {
                    shuttle.clearTowingInfo();
                }
                vehicle.setTowedByUUID("");
            }
            landing = departure;
            dock();
            cancel();
        }
    }

    private static void vector(CompoundTag tag, String key, Vec3 value) {
        CompoundTag vector = new CompoundTag();
        vector.putDouble("x", value.x);
        vector.putDouble("y", value.y);
        vector.putDouble("z", value.z);
        tag.put(key, vector);
    }

    private static Vec3 vector(CompoundTag tag, String key) {
        var vector = tag.getCompound(key);
        return new Vec3(vector.getDouble("x"), vector.getDouble("y"), vector.getDouble("z"));
    }

    private void ground(Vec3 destination, EngineInfo engine) {
        double rate;
        double steering;
        if (engine instanceof EngineInfo.Wheel wheel) {
            rate = wheel.getMaxForwardSpeedRate();
            steering = Math.toDegrees(wheel.getSteeringSpeed());
        } else if (engine instanceof EngineInfo.Ship ship) {
            rate = ship.getMaxForwardSpeedRate();
            steering = Math.toDegrees(ship.getSteeringSpeed());
        } else {
            engine.work(vehicle);
            return;
        }
        double maximum = 0.15 * rate / (1 - 1.05 * 0.79);
        double submerged = VehicleVecUtils.getSubmergedHeight(vehicle);
        if (engine instanceof EngineInfo.Ship) {
            maximum = DirectVehicleMotion.shipSpeed(rate, submerged);
        }
        double normalMaximum = maximum;
        maximum *= sprint ? 1.3 : 1;
        double acceleration = Math.max(Math.ulp(1f), maximum * engine.getIncrement());
        double braking = Math.max(Math.ulp(1f), maximum * engine.getDecrement() * 4);
        Vec3 delta = destination.subtract(vehicle.position()).multiply(1, 0, 1);
        double distance = delta.length();
        double reach = Math.max(braking, vehicle.getBbWidth() / 2);
        boolean stopped = order.action() == Vehicles.Action.STOP || order.action() == Vehicles.Action.ATTACK
            || points.isEmpty() && action.equals("HOLD");
        double speed = stopped ? 0 : Math.min(maximum, Math.sqrt(2 * braking * Math.max(0, distance - reach)));
        Vec3 navigation = stopped ? delta : separation.steer(destination).subtract(vehicle.position());
        turn(navigation, steering);
        speed *= Math.max(0, navigation.normalize().dot(vehicle.getViewVector(1).multiply(1, 0, 1).normalize()));
        Vec3 wanted = vehicle.getViewVector(1).multiply(1, 0, 1).normalize().scale(speed);
        Vec3 current = vehicle.getDeltaMovement().multiply(1, 0, 1);
        Vec3 velocity = approach(current, wanted, speed < current.length() ? braking : acceleration);
        vehicle.setPower((float) (velocity.length() / Math.max(Math.ulp(1f), normalMaximum)));
        double vertical = vehicle.getDeltaMovement().y + engine.getBuoyancy() * submerged;
        vertical *= vehicle.isInFluidType() ? 0.85 : 0.99;
        if (!vehicle.onGround() && !vehicle.isInFluidType()) {
            velocity = current.scale(0.99);
        } else if (engine instanceof EngineInfo.Ship && !vehicle.isInFluidType()) {
            velocity = current.scale(0.75);
        }
        velocity = separation.limit(velocity, braking, false);
        vehicle.setDeltaMovement(velocity.add(0, vertical, 0));
        if (distance <= reach && velocity.length() <= braking) {
            vehicle.setDeltaMovement(0, vehicle.getDeltaMovement().y, 0);
            advance();
        }
    }

    private void helicopter(Vec3 destination, EngineInfo.Helicopter engine) {
        double gravity = vehicle.computed().getGravity();
        double lift = engine.getLiftSpeed();
        double thrust = 0.12 * 0.66 * lift;
        double climb = Math.max(0, (thrust - gravity) / (1 - 0.95));
        double acceleration = Math.max(Math.ulp(1f), 0.007 * engine.getIncrement() * 0.66 * lift);
        double braking = Math.max(Math.ulp(1f), 0.005 * engine.getDecrement() * 0.66 * lift);
        if (settle && points.isEmpty() && landing == null) {
            // Stop hovers where the hull can come to rest, rather than overshooting and flying back.
            Vec3 moving = vehicle.getDeltaMovement().multiply(1, 0, 1);
            hold = vehicle.position().add(moving.scale(moving.length() / (2 * braking)));
            destination = hold;
        }
        settle = false;
        Vec3 delta = destination.subtract(vehicle.position());
        double reach = Math.max(braking, vehicle.getBbWidth() / 2);
        double altitude = destination.y;
        double floor = Math.max(4, vehicle.getBbHeight() * Math.max(1.5, lift));
        if (!departed && vehicle.getY() > hold.y + floor - .5) {
            departed = true;
            if (departure != null) {
                VehicleRunwayTraffic.release(vehicle);
                departure = null;
            }
        }
        // En route the band is held over the terrain ahead; a waypoint reached or a stop hovers where it is.
        boolean cruising = landing == null && (!points.isEmpty() || order.action() == Vehicles.Action.TAKE_OFF);
        if (cruising) {
            var path = new ArrayList<Vec3>(points.subList(Math.min(point, points.size()), points.size()));
            double band = height(Vehicles.altitude(vehicle), false, floor);
            altitude = cruise(path, band, engine.getSpeed(), climb / 2);
            if (!departed) {
                altitude = Math.max(altitude, hold.y + floor);
            }
            // Near where it lifted off, the band counts from that surface: a deck the heightmap cannot see.
            if (liftedFrom != null && vehicle.position().subtract(liftedFrom).horizontalDistance() < 32) {
                altitude = Math.max(altitude, liftedFrom.y + band);
            }
        }
        altitude = Math.min(altitude, ceiling());
        if (landing != null && delta.horizontalDistance() > reach) {
            altitude = Math.max(vehicle.getY(), destination.y + vehicle.getBbHeight() * lift);
        } else if (landing != null) {
            // Settle onto the pad, not just level with it: the deck has to be touched for a touchdown to count.
            altitude = destination.y - .25;
        }
        double vertical = altitude - vehicle.getY();
        double horizontal = Math.min(engine.getSpeed(), Math.sqrt(2 * braking
            * Math.max(0, delta.horizontalDistance() - reach)));
        if (cruising && !departed) {
            // Lift clear of the pad and anything parked beside it before setting off.
            horizontal = 0;
        }
        double verticalLimit = vertical < 0 ? Math.min(climb, 0.4) : climb;
        double verticalSpeed = Math.copySign(Math.min(verticalLimit, Math.sqrt(2 * braking * Math.abs(vertical))),
            vertical);
        Vec3 navigation = separation.steer(destination).subtract(vehicle.position());
        Vec3 wanted = navigation.multiply(1, 0, 1).normalize().scale(horizontal).add(0, verticalSpeed, 0);
        if (Math.abs(vertical) <= braking) {
            wanted = new Vec3(wanted.x, vertical, wanted.z);
        }
        var runway = landing != null ? landing : departure;
        Vec3 platform = runway == null ? Vec3.ZERO : runway.pose(vehicle.level())
            .map(LandingSites.Pose::velocity).orElse(Vec3.ZERO);
        Vec3 current = vehicle.getDeltaMovement().subtract(platform);
        // Collective answers faster than the rotor disc tilts: height is caught within a few blocks of a stop.
        Vec3 flat = approach(current.multiply(1, 0, 1), wanted.multiply(1, 0, 1),
            wanted.horizontalDistance() < current.horizontalDistance() ? braking : acceleration);
        double verticalRate = Math.max(acceleration, gravity / 4);
        Vec3 velocity = flat.add(0, current.y + Mth.clamp(wanted.y - current.y, -verticalRate, verticalRate), 0);
        turn(delta, engine.getYawSpeed());
        vehicle.setXRot(Mth.approachDegrees(vehicle.getXRot(), 0, engine.getPitchSpeed()));
        vehicle.setZRot(Mth.approachDegrees(vehicle.getRoll(), 0, engine.getRollSpeed()));
        vehicle.setPower((float) Mth.clamp((gravity + velocity.y * (1 - 0.95)) / (0.66 * lift), 0, 0.12));
        rotors();
        // VehicleEntity subtracts gravity immediately before move(), after this engine boundary.
        velocity = separation.limit(velocity.add(platform), braking, true).subtract(platform);
        vehicle.setDeltaMovement(velocity.add(platform).add(0, gravity, 0));
        if (landing != null && vehicle.onGround() && delta.horizontalDistance() <= reach) {
            vehicle.setDeltaMovement(0, 0, 0);
            vehicle.setPower(0);
            action = "LANDED";
            dock();
        } else if (landing == null && delta.horizontalDistance() <= reach && Math.abs(vertical) <= braking) {
            boolean routed = !points.isEmpty();
            advance();
            if (routed && points.isEmpty()) {
                // Arrived: hover at the band's height over the destination instead of sinking to the clicked ground.
                hold = new Vec3(hold.x, vehicle.getY(), hold.z);
            }
        }
    }

    private void aircraft(Vec3 destination, EngineInfo.Aircraft engine) {
        if (departure != null && departure.pose(vehicle.level()).isEmpty()) {
            cancel();
            departed = VehicleAirports.airborne(vehicle);
            action = "HOLD";
            vehicle.setPower(0);
            vehicle.setDeltaMovement(vehicle.getDeltaMovement().multiply(1, 0, 1)
                .add(0, vehicle.computed().getGravity(), 0));
            return;
        }
        double gravity = vehicle.computed().getGravity();
        double minimum = DirectVehicleMotion.minimumSpeed(engine, gravity);
        double powerLimit = landing != null || departure != null ? 1 : sprint ? 3 : 1;
        double maximum = DirectVehicleMotion.maximumSpeed(engine, powerLimit);
        double acceleration = Math.max(Math.ulp(1f), maximum * 0.006 * engine.getIncrement());
        double braking = Math.max(Math.ulp(1f), maximum * 0.006 * engine.getDecrement());
        var runway = landing != null ? landing : departure;
        Vec3 platform = runway == null ? Vec3.ZERO : runway.pose(vehicle.level())
            .map(LandingSites.Pose::velocity).orElse(Vec3.ZERO);
        double speed = vehicle.getDeltaMovement().subtract(platform).horizontalDistance();
        // A parked aircraft with nothing to do stays parked: only an order starts its takeoff roll.
        if (order.action() == Vehicles.Action.STOP && !departed) {
            vehicle.setDeltaMovement(platform.add(0, gravity, 0));
            vehicle.setPower(0);
            return;
        }
        if (departure != null && VehicleAirports.airborne(vehicle, departure)
            && (launch == null || !launch.seated())) {
            if (launch != null) {
                launch.cancel();
                launch = null;
            }
            VehicleRunwayTraffic.release(vehicle);
            departure = null;
            departed = true;
        }
        if (departure != null && !VehicleRunwayTraffic.acquire(vehicle, departure)) {
            vehicle.setDeltaMovement(platform.add(0, gravity, 0));
            vehicle.setPower(0);
            action = "RUNWAY_WAIT";
            return;
        }
        if ((launch == null || !launch.seated()) && !VehicleAirports.airborne(vehicle) && separation.limit(
            vehicle.getViewVector(1).multiply(1, 0, 1).normalize().scale(Math.max(speed, 0.2)),
            braking, false).horizontalDistance() < Math.max(speed, 0.2) * 0.9) {
            vehicle.setDeltaMovement(platform.add(0, gravity, 0));
            vehicle.setPower(0);
            return;
        }
        if (launch != null && launch.tick(engine)) {
            action = "CATAPULT";
            return;
        }
        double radius = Math.max(vehicle.getBbWidth(), Math.max(minimum, speed) / Math.toRadians(engine.getYawSpeed()));
        double clearance = vehicle.getBbHeight() + minimum * minimum / Math.max(Math.ulp(1f), gravity);
        if (vehicle.getY() > hold.y + vehicle.getBbHeight()) {
            departed = true;
            if (departure != null) {
                VehicleRunwayTraffic.release(vehicle);
                departure = null;
            }
        }
        boolean takeoff = !departed && landing == null;
        if (takeoff) {
            destination = vehicle.position().add(vehicle.getViewVector(1).multiply(1, 0, 1).scale(radius));
            destination = destination.add(0, speed >= minimum ? clearance : 0, 0);
            if (departure != null) {
                var pose = departure.pose(vehicle.level()).orElse(null);
                if (pose == null) {
                    VehicleRunwayTraffic.release(vehicle);
                    departure = null;
                    departed = VehicleAirports.airborne(vehicle);
                    hold = vehicle.position();
                    action = "HOLD";
                    return;
                }
                double along = vehicle.position().subtract(pose.position()).dot(pose.forward());
                destination = pose.position().add(pose.forward().scale(along + radius))
                    .add(pose.up().scale(speed >= minimum ? clearance : 0));
            }
        } else if (landing == null && points.isEmpty()) {
            if (orbit == null) {
                orbit = vehicle.position().add(vehicle.getRightVec(1).scale(radius));
            }
            Vec3 radial = vehicle.position().subtract(orbit).multiply(1, 0, 1).normalize();
            destination = orbit.add(radial.scale(radius)).add(-radial.z * radius, 0, radial.x * radius);
            action = "LOITER";
        }
        // Cruise and loiter hold the band over the terrain ahead, along the heading and toward the destination.
        boolean cruising = !takeoff && landing == null;
        if (cruising) {
            double fast = Math.max(minimum, speed);
            double rate = fast * Math.sin(Math.toRadians(Math.min(30, engine.getClampPitch()))) / 2;
            double above = height(Vehicles.altitude(vehicle), true, fixedWingFloor());
            Vec3 ahead = vehicle.position().add(vehicle.getViewVector(1).multiply(1, 0, 1).normalize()
                .scale(lookahead(fast, rate)));
            double target = Math.max(cruise(List.of(ahead), above, fast, rate),
                cruise(List.of(destination), above, fast, rate));
            destination = new Vec3(destination.x, Math.min(target, ceiling()), destination.z);
        }
        double landingHeight = Double.POSITIVE_INFINITY;
        if (landing == null || !approachAligned) {
            groundContactTicks = 0;
        }
        if (landing != null) {
            var pose = landing.pose(vehicle.level()).orElse(null);
            if (pose == null) {
                landing = null;
                hold = vehicle.position();
                action = "HOLD";
                return;
            }
            platform = pose.velocity();
            boolean runwayFree = touchdown || VehicleRunwayTraffic.acquire(vehicle, landing);
            Vec3 relative = vehicle.position().subtract(pose.position());
            landingHeight = relative.dot(pose.up());
            double alongRunway = relative.dot(pose.forward());
            double acrossRunway = relative.dot(pose.forward().cross(pose.up()));
            // onGround also includes rotated body contact in SW. Require the hull footing on this surface.
            boolean overRunway = alongRunway >= 0 && alongRunway <= landing.length()
                && Math.abs(acrossRunway) + vehicle.getBbWidth() / 2 <= landing.width() / 2;
            boolean runwayContact = runwayFree && approachAligned && overRunway && Math.abs(landingHeight) <= 0.15
                && runwayFooting(pose);
            groundContactTicks = runwayContact ? groundContactTicks + 1 : 0;
            if (!runwayFree) {
                orbit = pose.position().subtract(pose.forward().scale(radius * 4));
                Vec3 radial = vehicle.position().subtract(orbit).multiply(1, 0, 1).normalize();
                destination = orbit.add(radial.scale(radius)).add(-radial.z * radius, clearance, radial.x * radius);
                action = "RUNWAY_WAIT";
            }
            Vec3 intercept = pose.position().subtract(pose.forward().scale(radius * 2)).add(pose.up().scale(clearance));
            if (runwayFree && vehicle.position().distanceTo(intercept) <= radius) {
                approachAligned = true;
            }
            if (runwayFree && !approachAligned) {
                destination = intercept;
            } else if (runwayFree) {
                double along = relative.dot(pose.forward());
                double lookahead = Math.max(vehicle.getBbWidth(), speed);
                double targetAlong = Math.min(landing.length(), along + lookahead);
                double height = Math.max(0, -targetAlong) * clearance / (radius * 2);
                destination = pose.position().add(pose.forward().scale(targetAlong))
                    .add(pose.up().scale(height));
            }
        }
        boolean grounded = landing != null && (touchdown || groundContactTicks >= 3);
        double sink = Math.max(0, platform.y - vehicle.getDeltaMovement().y);
        boolean flare = landing != null && approachAligned && landingHeight <= Math.max(3, sink * 30);
        if (!takeoff && !grounded) {
            Vec3 separated = separation.flight(destination);
            if (landing != null && separated.y > destination.y + 0.1) {
                // A traffic conflict aborts the final descent; rejoin the approach after it clears.
                approachAligned = false;
                groundContactTicks = 0;
                flare = false;
            }
            destination = new Vec3(separated.x, Math.min(separated.y, ceiling()), separated.z);
        }
        Vec3 delta = destination.subtract(vehicle.position());
        double yaw = turn(delta, engine.getYawSpeed());
        // Cruising, height is chased over a short horizon rather than the whole leg, so it is reached in time.
        double pitch = -Math.toDegrees(Math.atan2(delta.y, cruising
            ? Math.min(delta.horizontalDistance(), Math.max(vehicle.getBbWidth(), Math.max(minimum, speed) * 20))
            : delta.horizontalDistance()));
        pitch = Mth.clamp(pitch, -Math.min(engine.getClampPitch(), 90), Math.min(engine.getClampPitch(), 90));
        float pitchRate = flare || grounded ? Math.min(0.5f, engine.getPitchSpeed()) : engine.getPitchSpeed();
        if (flare) {
            pitch = 0;
        }
        if (grounded) {
            pitch = 0;
        }
        vehicle.setXRot(Mth.approachDegrees(vehicle.getXRot(), (float) pitch, pitchRate));
        double bank = Math.toDegrees(Math.atan(Math.max(minimum, speed) * Math.toRadians(yaw) / gravity));
        vehicle.setZRot(Mth.approachDegrees(vehicle.getRoll(),
            grounded ? 0 : (float) Mth.clamp(-bank, -Math.min(engine.getClampRoll(), 90),
                Math.min(engine.getClampRoll(), 90)),
            flare || grounded ? Math.min(0.5f, engine.getRollSpeed()) : engine.getRollSpeed()));
        double wanted = landing != null && approachAligned ? minimum : maximum;
        if (grounded) {
            touchdown = true;
            wanted = 0;
            action = "LANDED";
            if (speed <= braking) {
                VehicleRunwayTraffic.release(vehicle);
                dock();
            }
        }
        speed += Mth.clamp(wanted - speed, -braking, acceleration);
        if (!takeoff && landing == null) {
            speed = Math.max(minimum, speed);
        }
        Vec3 velocity = vehicle.getViewVector(1).scale(speed);
        if (grounded) {
            velocity = velocity.multiply(1, 0, 1);
        } else if (flare) {
            // Separate sink from nose attitude; do not drive the hull down through the pavement.
            double descent = Mth.clamp(landingHeight * 0.04, 0.04, 0.12);
            double previous = vehicle.getDeltaMovement().y - platform.y;
            double vertical = previous + Mth.clamp(-descent - previous, -0.01, 0.01);
            velocity = new Vec3(velocity.x, Math.max(-0.12, vertical), velocity.z);
        }
        if (takeoff && speed < minimum) {
            velocity = velocity.multiply(1, 0, 1);
        }
        vehicle.setPower((float) Mth.clamp(speed / Math.max(Math.ulp(1f), maximum) * powerLimit,
            0, powerLimit));
        rotors();
        vehicle.setDeltaMovement(velocity.add(platform).add(0, gravity, 0));
        if (!points.isEmpty() && delta.horizontalDistance() <= radius) {
            advance();
        }
    }

    private void airship(Vec3 destination, EngineInfo.AirShip engine) {
        // SW uses speed-dependent drag, .01*Power*TargetSpeed thrust, and .05-scaled power changes.
        double maximum = DirectVehicleMotion.airshipSpeed(engine.getMaxForwardSpeedRate()
            * (sprint ? engine.getSprintMultiply() : 1));
        double acceleration = Math.max(Math.ulp(1f), maximum * 0.05 * engine.getIncrement());
        double braking = Math.max(Math.ulp(1f), maximum * 0.05 * engine.getDecrement());
        double terrain = vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING,
            vehicle.getBlockX(), vehicle.getBlockZ());
        double altitude = Math.min(vehicle.level().getMaxBuildHeight() - vehicle.getBbHeight(),
            Math.max(destination.y, terrain + engine.getFloatHeight()));
        Vec3 delta = new Vec3(destination.x, altitude, destination.z).subtract(vehicle.position());
        double reach = Math.max(vehicle.getBbWidth() / 2, braking);
        double horizontal = Math.min(maximum, Math.sqrt(2 * braking
            * Math.max(0, delta.horizontalDistance() - reach)));
        double upward = Math.max(0, (0.06 * engine.getMaxUpSpeedRate() - vehicle.computed().getGravity()) / 0.1);
        double downward = (0.06 * Math.abs(engine.getMaxDownSpeedRate()) + vehicle.computed().getGravity()) / 0.1;
        double vertical = Mth.clamp(delta.y, -downward, upward);
        Vec3 wanted = delta.multiply(1, 0, 1).normalize().scale(horizontal).add(0, vertical, 0);
        Vec3 velocity = approach(vehicle.getDeltaMovement(), wanted, acceleration);
        turn(delta, Math.toDegrees(0.05 * engine.getSteeringSpeed()));
        vehicle.setPower((float) Mth.clamp(velocity.horizontalDistance() / Math.max(Math.ulp(1f), maximum), 0, 1));
        vehicle.setDeltaMovement(velocity.add(0, vehicle.computed().getGravity(), 0));
        if (delta.horizontalDistance() <= reach && Math.abs(delta.y) <= braking) {
            advance();
        }
    }

    /** Blocks above the terrain each band means for an airframe, never below its safe clearance. */
    static double height(Vehicles.Altitude band, boolean fixedWing, double floor) {
        double height = switch (band) {
            case LOW -> fixedWing ? floor : 6;
            case CRUISE -> fixedWing ? 40 : 24;
            case HIGH -> fixedWing ? 80 : 48;
        };
        return Math.max(floor, height);
    }

    /** The lowest a fixed wing flies over terrain, with room to pull up over what the look-ahead finds. */
    private double fixedWingFloor() {
        return Math.max(16, vehicle.getBbHeight() * 2);
    }

    private double ceiling() {
        return vehicle.level().getMaxBuildHeight() - vehicle.getBbHeight() - 2;
    }

    /** How far ahead terrain matters: the distance flown while climbing a tall hill, within a sane bound. */
    private static double lookahead(double speed, double climb) {
        return Mth.clamp(speed * 64 / Math.max(climb, 1e-3), 16, 256);
    }

    /**
     * The height to hold now: the band above the terrain here, and above every point of the path ahead less what
     * can still be climbed before reaching it, so the climb starts in time for a hill.
     */
    private double cruise(List<Vec3> path, double above, double speed, double climb) {
        speed = Math.max(speed, 1e-3);
        double reach = lookahead(speed, climb);
        double step = Math.max(2, reach / 64);
        double here = terrain(vehicle.position());
        // Over unloaded ground nothing is known: hold the current height rather than chase a guess.
        double best = Double.isNaN(here) ? vehicle.getY() : here + above;
        Vec3 from = vehicle.position();
        double travelled = 0;
        for (Vec3 to : path) {
            Vec3 leg = to.subtract(from).multiply(1, 0, 1);
            double length = leg.length();
            for (double along = step; along <= length && travelled + along <= reach; along += step) {
                double ahead = travelled + along;
                double ground = terrain(from.add(leg.scale(along / length)));
                if (!Double.isNaN(ground)) {
                    best = Math.max(best, ground + above - ahead * climb / speed);
                }
            }
            travelled += length;
            from = new Vec3(to.x, from.y, to.z);
            if (travelled >= reach) {
                break;
            }
        }
        return best;
    }

    /**
     * The highest motion-blocking surface under the hull's footprint, or NaN where no chunk is loaded: terrain is
     * never loaded for it.
     */
    private double terrain(Vec3 at) {
        double half = vehicle.getBbWidth() / 2;
        double top = Double.NEGATIVE_INFINITY;
        for (int sample = 0; sample < 5; sample++) {
            int x = Mth.floor(at.x + (sample == 1 ? half : sample == 2 ? -half : 0));
            int z = Mth.floor(at.z + (sample == 3 ? half : sample == 4 ? -half : 0));
            if (vehicle.level().hasChunk(x >> 4, z >> 4)) {
                top = Math.max(top, vehicle.level().getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        return Double.isFinite(top) ? top : Double.NaN;
    }

    private boolean runwayFooting(LandingSites.Pose pose) {
        AABB bounds = vehicle.getBoundingBox().move(pose.up().scale(-0.05));
        if (landing.position().realm() instanceof Realm.Frame frame) {
            var space = WorldSpaces.frame(vehicle.level(), frame.structure()).orElse(null);
            return space != null && vehicle.level().getBlockCollisions(vehicle, storage(space, bounds)).iterator()
                .hasNext();
        }
        return vehicle.level().getBlockCollisions(vehicle, bounds).iterator().hasNext();
    }

    /**
     * Whether the hull rests on pavement or a deck. Controlled travel compensates gravity, so onGround can be
     * false even at rest. Sable deck collisions additionally need the structure's plot coordinates.
     */
    private boolean onSurface() {
        if (!(WorldSpaces.at(vehicle).realm() instanceof Realm.Frame frame)) {
            return vehicle.level().getBlockCollisions(vehicle, vehicle.getBoundingBox().move(0, -0.05, 0))
                .iterator().hasNext();
        }
        var space = WorldSpaces.frame(vehicle.level(), frame.structure()).orElse(null);
        if (space == null) {
            return false;
        }
        Vec3 up = space.toWorld(space.toLocal(vehicle.position()).add(0, 1, 0)).subtract(vehicle.position());
        return vehicle.level().getBlockCollisions(vehicle,
            storage(space, vehicle.getBoundingBox().move(up.scale(-0.05)))).iterator().hasNext();
    }

    // Sable stores deck blocks in plot coordinates, outside the world-space collision query.
    private static AABB storage(WorldSpaces.Frame space, AABB bounds) {
        AABB local = null;
        for (int corner = 0; corner < 8; corner++) {
            Vec3 world = new Vec3((corner & 1) == 0 ? bounds.minX : bounds.maxX,
                (corner & 2) == 0 ? bounds.minY : bounds.maxY,
                (corner & 4) == 0 ? bounds.minZ : bounds.maxZ);
            Vec3 storage = space.toLocal(world).add(Vec3.atLowerCornerOf(space.storageOrigin()));
            AABB point = new AABB(storage, storage);
            local = local == null ? point : local.minmax(point);
        }
        return local;
    }

    private void dock() {
        if (landing != null) {
            VehicleAirports.landed(vehicle, landing.airport().id(), order.airport().isPresent());
        }
        if (landing == null || !(landing.position().realm() instanceof Realm.Frame frame)) {
            return;
        }
        WorldSpaces.frame(vehicle.level(), frame.structure()).ifPresent(space -> {
            dock = space;
            dockId = space.id();
            dockLocal = space.toLocal(vehicle.position());
            dockForward = space.toLocal(vehicle.position().add(vehicle.getViewVector(1))).subtract(dockLocal);
            dockUp = space.toLocal(vehicle.position().add(vehicle.getUpVec(1))).subtract(dockLocal);
        });
    }

    private float turn(Vec3 delta, double rate) {
        if (delta.horizontalDistanceSqr() == 0) {
            return 0;
        }
        float error = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)) - vehicle.getYRot());
        float change = (float) Mth.clamp(error, -rate, rate);
        vehicle.setYRot(vehicle.getYRot() + change);
        return change;
    }

    private void rotors() {
        vehicle.setEngineStart(vehicle.getPower() > 0);
        vehicle.setEngineStartOver(vehicle.getPower() > 0);
        vehicle.setSynchedPropellerRot(vehicle.getPower());
        vehicle.setPropellerRot(vehicle.getPropellerRot() + 30 * vehicle.getPower());
    }

    private static Vec3 approach(Vec3 current, Vec3 wanted, double limit) {
        Vec3 change = wanted.subtract(current);
        return change.length() <= limit ? wanted : current.add(change.normalize().scale(limit));
    }

    private void advance() {
        if (points.isEmpty()) {
            return;
        }
        if (point + 1 < points.size()) {
            point++;
        } else if (order.cycleStart() >= 0) {
            point = order.cycleStart();
        } else {
            hold = separation.destination(points.getLast());
            points = List.of();
            action = "HOLD";
        }
    }
}
