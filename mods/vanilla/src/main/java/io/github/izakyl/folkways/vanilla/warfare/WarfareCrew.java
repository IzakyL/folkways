package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.combat.api.VehicleProfiles;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.command.api.CrewSelection;
import io.github.izakyl.folkways.core.shell.warfare.VehicleBoarding;
import io.github.izakyl.folkways.core.shell.warfare.VehicleController;
import io.github.izakyl.folkways.core.shell.warfare.VehicleSeats;
import io.github.izakyl.folkways.core.shell.warfare.VehicleTraffic;
import io.github.izakyl.folkways.core.shell.warfare.WarfareVehicles;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class WarfareCrew extends WarfareVehicles {
    static final ResourceLocation ID = VehicleSeats.POST_SOURCE;
    static final ResourceLocation CREW = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "vehicle_crew");

    private final Map<VehicleController, VehicleTactics> tactics = new HashMap<>();

    private VehicleTactics tactics(VehicleEntity vehicle) {
        VehicleController pilot = VehicleController.of(vehicle);
        return tactics.computeIfAbsent(pilot, key -> new VehicleTactics(vehicle, key));
    }

    @Override
    protected void forget(VehicleController pilot) {
        tactics.remove(pilot);
    }

    @Override
    protected void routineStarted(Colony colony, ServerPlayer player, VehicleEntity vehicle, String routine) {
        if (routine.equals("move_to")) {
            var route = status(vehicle).route();
            CrewSelection.trackVehicle(colony, player, vehicle,
                new Vehicles.Order(Vehicles.Action.MOVE, route.points(), route.cycleStart(),
                    Optional.empty(), Optional.empty()));
        }
    }

    @Override
    protected void tickCrew(VehicleEntity vehicle, VehicleController pilot) {
        ServerLevel level = (ServerLevel) vehicle.level();
        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            Optional<UUID> assigned = VehicleSeats.assigned(vehicle, seat);
            if (assigned.isEmpty()) {
                continue;
            }
            Entity member = level.getEntity(assigned.orElseThrow());
            if (member != null && (!member.isAlive() || !(member instanceof LivingEntity living)
                || !VehicleSeats.matches(living, vehicle, seat) || Bodies.of(member)
                .filter(body -> body.colonyId().equals(VehicleSeats.owner(vehicle))).isEmpty())) {
                VehicleSeats.release(vehicle, seat);
            }
        }
        if (vehicle.getPassengers().stream().anyMatch(passenger -> !(passenger instanceof Mob mob)
            || !VehicleSeats.holds(vehicle, mob))) {
            pilot.cancel();
            return;
        }
        tactics(vehicle).tick();
    }

    @Override
    protected List<Vehicles.Firing> firing(VehicleEntity vehicle) {
        return tactics(vehicle).firing();
    }

    @Override
    protected VehicleTraffic traffic(VehicleEntity vehicle) {
        return new ColonyVehicleTraffic(vehicle);
    }

    @Override
    protected double height(VehicleEntity vehicle, boolean fixedWing, double floor) {
        return VehicleTactics.height(Vehicles.altitude(vehicle), fixedWing, floor);
    }

    @Override
    protected double sprintFactor(VehicleEntity vehicle) {
        return vehicle.getEngineInfo() instanceof EngineInfo.Aircraft ? 3
            : vehicle.getEngineInfo() instanceof EngineInfo.AirShip airship ? airship.getSprintMultiply() : 1.3;
    }

    @Override
    protected int nextPoint(Vehicles.Order order, int point, int count) {
        return VehicleTactics.nextPoint(order, point, count);
    }

    @Override
    protected boolean maySwap(VehicleEntity vehicle, Body body, Entity other, int from) {
        return from >= 0 && other.getVehicle() == vehicle && VehicleSeats.holds(vehicle, body.mob());
    }

    @Override
    public Vehicles.Result leave(Entity entity, int seat) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (vehicle.getLocked()) {
            return Vehicles.Result.refused("unavailable");
        }
        if (!VehicleController.atRest(vehicle)) {
            return Vehicles.Result.refused("airborne");
        }
        return super.leave(entity, seat);
    }

    @Override
    public Vehicles.Result releaseOwnership(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        Vehicles.Result result = leave(vehicle, -1);
        if (result.accepted()) {
            VehicleController.of(vehicle).cancel();
            VehicleSeats.clear(vehicle);
            vehicle.getPersistentData().remove(Vehicles.RELEASE_IDLE);
        }
        return result;
    }

    @Override
    protected boolean available(VehicleEntity vehicle, UUID colony) {
        return super.available(vehicle, colony)
            && vehicle.getPassengers().stream().allMatch(passenger -> Bodies.of(passenger)
                .filter(body -> body.colonyId().filter(colony::equals).isPresent()).isPresent());
    }

    @Override
    public Vehicles.Result order(Entity entity, Vehicles.Order order) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : tactics((VehicleEntity) entity).order(order);
    }

    @Override
    protected Vehicles.Result destination(VehicleEntity vehicle, WorldPos destination) {
        Vehicles.Action action = airframe(vehicle) == Vehicles.Airframe.FIXED_WING
            ? Vehicles.Action.LOITER : Vehicles.Action.MOVE;
        return order(vehicle, new Vehicles.Order(action, List.of(destination), -1, Optional.empty(), Optional.empty()));
    }

    @Override
    public Vehicles.Result appendPoint(Entity entity, WorldPos point, boolean attackMove) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : tactics((VehicleEntity) entity).append(point, attackMove);
    }

    @Override
    public Vehicles.Result cycle(Entity entity, int start) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : tactics((VehicleEntity) entity).cycle(start);
    }

    static void declare(Declaring event) {
        Posts.register(WarfareCrew::posts);
        event.vocation(new VocationSpec(CREW, List.of()).ordered(711));
        event.colony(ID, scope -> {
            var supply = new VehicleSupply(scope.colony());
            scope.tick(supply::tick);
        });
        event.urges(Commands.ORDERS, (colony, who, view) -> urges(who));
    }

    static List<Urge> urges(Worker who) {
        CompoundTag loan = who.body().getPersistentData().getCompound(VehicleSeats.SEAT);
        if (!loan.hasUUID("vehicle") || !(who.body().level() instanceof ServerLevel level)) {
            return List.of();
        }
        Entity entity = level.getEntity(loan.getUUID("vehicle"));
        if (!(entity instanceof VehicleEntity vehicle)) {
            if (!loan.contains("absentSince")) {
                loan.putLong("absentSince", level.getGameTime());
            }
            if (level.getGameTime() - loan.getLong("absentSince") < 200) {
                return List.of(Urge.reserve(ID));
            }
            who.body().getPersistentData().remove(VehicleSeats.SEAT);
            return List.of();
        }
        loan.remove("absentSince");
        int seat = loan.getInt("seat");
        if (vehicle.isWreck() || vehicle.isRemoved()) {
            VehicleSeats.clear(vehicle);
            who.body().getPersistentData().remove(VehicleSeats.SEAT);
            return List.of();
        }
        if (!VehicleSeats.assigned(vehicle, seat).filter(who.resident().id()::equals).isPresent()) {
            who.body().getPersistentData().remove(VehicleSeats.SEAT);
            return List.of();
        }
        if (who.body().getVehicle() != vehicle && level.getGameTime() - loan.getLong("assignedAt") >= 1200) {
            VehicleSeats.release(vehicle, seat);
            return List.of();
        }
        return VehicleSeats.owner(vehicle).flatMap(id -> Colonies.of(level.getServer(), id)).flatMap(Posts::of)
            .map(posts -> {
                var station = new VehiclePost(vehicle, seat, who.body());
                var intent = ID.withPath(ID.getPath() + "/" + vehicle.getUUID() + "/" + seat);
                // Keep the passive reservation while boarding or temporarily yielding to a personal urge.
                return List.of(posts.urge(station.post(level), () -> station.demanded(level), intent, Urge.DIRE,
                    true, Disposition.END), Urge.reserve(ID));
            }).orElseGet(List::of);
    }

    private static List<Post> posts(ServerLevel level, Colony colony) {
        List<Post> found = new ArrayList<>();
        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof VehicleEntity vehicle) || vehicle.isWreck() || vehicle.getLocked()
                    || VehicleSeats.owner(vehicle).filter(colony.id()::equals).isEmpty()) {
                continue;
            }
            for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
                var occupant = vehicle.getNthEntity(seat);
                found.add(new VehiclePost(vehicle, seat, occupant instanceof Mob mob ? mob : null).post(level));
            }
        }
        return found;
    }

    static boolean crewAllowed(Body body) {
        return body.licences().trade(Vocations.required(CREW)).filter(body.licences()::allows).isPresent();
    }

    @Override
    protected boolean mayBoard(Body body) {
        return crewAllowed(body);
    }

    @Override
    protected void boarding(ServerLevel level, VehicleEntity vehicle, Body body, int seat) {
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .ifPresent(colony -> CrewSelection.board(colony, body.id(),
                vehicle.getSeat(seat).weapons().isEmpty() && seat != 0));
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)).ifPresent(colony -> {
            VehicleProfile profile = profile(vehicle, colony);
            profile.equipment().ifPresent(equipment ->
                EquipmentProfiles.of(colony).assign(List.of(body.id()), Optional.of(equipment)));
        });
    }

    static VehicleProfile profile(VehicleEntity vehicle, Colony colony) {
        var profiles = new VehicleProfiles(colony, vehicle.registryAccess());
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        VehicleProfile profile = profiles.assigned(vehicle).or(() -> profiles.defaultFor(type)).orElseGet(() -> {
            Set<Integer> required = Vehicles.of(vehicle).orElseThrow().seats(vehicle).stream()
                .filter(seat -> seat.index() == 0 || !seat.weapons().isEmpty())
                .map(Vehicles.Seat::index).collect(Collectors.toSet());
            var created = new VehicleProfile(UUID.randomUUID(), vehicle.getType().getDescriptionId(), type, required,
                Optional.empty(), List.of(), 0, Map.of());
            profiles.replace(created);
            return created;
        });
        profiles.bind(vehicle, profile.id(), type);
        return profile;
    }

    @Override
    public Optional<Vehicles.Readiness> readiness(Entity entity, Colony colony) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        VehicleProfile profile = profile(vehicle, colony);
        return Optional.of(new Vehicles.Readiness(profile.id(), profile.type(),
            profile.stock().stream().map(entry -> VehicleSupply.count(vehicle, entry)).toList(),
            profile.seats().stream().sorted().filter(seat -> !(vehicle.getNthEntity(seat) instanceof LivingEntity body)
                || !VehicleSeats.holds(vehicle, body)).toList()));
    }

    @Override
    public Map<String, WeaponRules.Rule> rules(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        Map<String, WeaponRules.Rule> rules = new LinkedHashMap<>();
        VehicleSeats.owner(vehicle).flatMap(id -> Colonies.of(vehicle.getServer(), id))
            .ifPresent(colony -> rules.putAll(profile(vehicle, colony).rules()));
        rules.putAll(super.rules(entity));
        return Map.copyOf(rules);
    }

    /** ETA uses the navigator's reachable path length and the candidate's movement speed. */
    @Override
    public Vehicles.Result boardAvailable(Entity entity, Colony colony, List<Body> selected) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (!available(vehicle, colony.id())) {
            return Vehicles.Result.refused("unavailable");
        }
        if (!selected.isEmpty()) {
            List<Vehicles.Seat> passengers = seats(vehicle).stream()
                .filter(seat -> seat.role() == Vehicles.Role.PASSENGER && seat.occupant().isEmpty()).toList();
            if (passengers.size() < selected.size() || selected.stream().anyMatch(body -> !crewAllowed(body)
                || !VehicleBoarding.hasFooting(vehicle, body.mob()))) {
                return Vehicles.Result.refused("no_passenger_seats");
            }
            for (int n = 0; n < selected.size(); n++) {
                Vehicles.Result result = board(vehicle, selected.get(n), passengers.get(n).index());
                if (!result.accepted()) {
                    return result;
                }
            }
            return Vehicles.Result.success();
        }
        ServerLevel level = (ServerLevel) vehicle.level();
        List<Body> people = selected.isEmpty() ? colony.residents().stream()
            .map(resident -> level.getEntity(resident.id())).filter(Objects::nonNull)
            .flatMap(member -> Bodies.of(member).stream())
            .filter(WarfareCrew::crewAllowed)
            .filter(body -> !CrewSelection.commanded(colony, body.id()))
            .toList() : selected;
        Map<Body, Double> eta = new LinkedHashMap<>();
        people = people.stream().sorted(Comparator.comparingDouble(body ->
            body.mob().distanceToSqr(vehicle))).limit(32).toList();
        for (Body body : people) {
            if (!body.colonyId().filter(colony.id()::equals).isPresent()) {
                continue;
            }
            var approach = VehicleBoarding.approach(vehicle, body.mob());
            var destination = approach.map(VehicleBoarding.Approach::cell);
            Path path = approach.map(VehicleBoarding.Approach::path).orElse(null);
            if (path == null) {
                // Ground navigation is temporarily unavailable before landing; walking retries after assignment.
                if (!body.mob().onGround() && destination.isPresent()) {
                    double length = VehicleBoarding.world(vehicle, destination.orElseThrow())
                        .distanceTo(body.mob().position());
                    eta.put(body, length / Math.max(.001, body.mob().getAttributeValue(Attributes.MOVEMENT_SPEED)));
                }
                continue;
            }
            if (!path.canReach()) {
                continue;
            }
            double length = 0;
            var last = body.mob().position();
            for (int n = 0; n < path.getNodeCount(); n++) {
                Vec3 next = VehicleBoarding.world(vehicle, path.getEntityPosAtNode(body.mob(), n));
                length += last.distanceTo(next);
                last = next;
            }
            eta.put(body, length / Math.max(.001, body.mob().getAttributeValue(Attributes.MOVEMENT_SPEED)));
        }
        List<Body> sorted = eta.keySet().stream().sorted(Comparator.comparingDouble(eta::get)).toList();
        VehicleProfile profile = profile(vehicle, colony);
        List<Vehicles.Seat> empty = seats(vehicle).stream().filter(seat -> seat.occupant().isEmpty())
            .filter(seat -> profile.seats().contains(seat.index()))
            .sorted(Comparator.comparing(Vehicles.Seat::role)).toList();
        int assigned = 0;
        for (Body body : sorted) {
            if (assigned == empty.size()) {
                break;
            }
            if (board(vehicle, body, empty.get(assigned).index()).accepted()) {
                assigned++;
            }
        }
        return assigned > 0 ? Vehicles.Result.success() : Vehicles.Result.refused("no_crew");
    }

}
