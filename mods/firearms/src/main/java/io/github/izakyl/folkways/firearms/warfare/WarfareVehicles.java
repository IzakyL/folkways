package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
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
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.rts.api.VehicleCrewCommands;
import io.github.izakyl.folkways.core.vocation.api.VocationSpec;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public final class WarfareVehicles implements Vehicles.Provider {
    static final WarfareVehicles INSTANCE = new WarfareVehicles();
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_firearms", "vehicle_control");

    static final ResourceLocation CREW = ResourceLocation.fromNamespaceAndPath("folkways_firearms", "vehicle_crew");

    private WarfareVehicles() {
    }

    static void declare(Declaring event) {
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
        boolean aboard = VehicleSeats.holds(vehicle, who.body());
        ResourceLocation intent = ID.withPath(ID.getPath() + "/" + vehicle.getUUID() + "/" + seat + "/" + aboard);
        // Crew cannot run ordinary routes while their seat duty keeps preempting them.
        return List.of(new Urge(intent, Urge.DIRE, true,
            () -> new VehicleSeatDuty(vehicle, who, seat, aboard), Urge.Priority.CONTROL).asUrgent(), Urge.reserve(ID));
    }

    @Override
    public boolean accepts(Entity entity) {
        return entity instanceof VehicleEntity vehicle && !vehicle.isRemoved() && !vehicle.isWreck()
            && vehicle.computed().getEngineType() != EngineType.FIXED
            && vehicle.computed().getEngineType() != EngineType.EMPTY;
    }

    @Override
    public boolean ownsSeat(Body body) {
        return body.mob().getVehicle() instanceof VehicleEntity vehicle && VehicleSeats.holds(vehicle, body.mob());
    }

    @Override
    public List<Vehicles.Seat> seats(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        List<Vehicles.Seat> result = new ArrayList<>();
        for (int seat = 0; seat < vehicle.getMaxPassengers(); seat++) {
            var info = vehicle.getSeat(seat);
            int index = seat;
            Optional<UUID> occupant = vehicle.getPassengers().stream()
                .filter(passenger -> vehicle.getSeatIndex(passenger) == index).map(Entity::getUUID).findFirst();
            result.add(new Vehicles.Seat(seat, seat == 0 ? Vehicles.Role.DRIVER
                : !info.weapons().isEmpty() ? Vehicles.Role.GUNNER : Vehicles.Role.PASSENGER,
                info.weapons(), vehicle.getWeaponIndex(seat),
                occupant.or(() -> VehicleSeats.assigned(vehicle, index))));
        }
        return List.copyOf(result);
    }

    @Override
    public Vehicles.Capabilities capabilities(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        Vehicles.Airframe airframe = airframe(vehicle);
        boolean flying = airframe != Vehicles.Airframe.GROUND;
        List<Vehicles.Action> actions = new ArrayList<>(List.of(Vehicles.Action.MOVE, Vehicles.Action.PATROL,
            Vehicles.Action.STOP, Vehicles.Action.ATTACK, Vehicles.Action.ATTACK_MOVE));
        if (vehicle.computed().getHasDecoy()) {
            actions.add(Vehicles.Action.DECOY);
        }
        if (vehicle.getEngineInfo() != null && vehicle.getEngineInfo().getSprintMultiply() > 1) {
            actions.add(Vehicles.Action.SPRINT);
        }
        if (flying) {
            actions.addAll(List.of(Vehicles.Action.TAKE_OFF, Vehicles.Action.LAND, Vehicles.Action.LOITER));
        }
        return new Vehicles.Capabilities(true, airframe,
            seats(entity).stream().anyMatch(seat -> !seat.weapons().isEmpty()), actions);
    }

    /** Superb Warfare's engine type decides the airframe; airships float like boats and count as ground. */
    static Vehicles.Airframe airframe(VehicleEntity vehicle) {
        EngineType type = vehicle.computed().getEngineType();
        return type == EngineType.HELICOPTER ? Vehicles.Airframe.HELICOPTER
            : type == EngineType.AIRCRAFT ? Vehicles.Airframe.FIXED_WING : Vehicles.Airframe.GROUND;
    }

    @Override
    public Optional<UUID> owner(Entity entity) {
        return VehicleSeats.owner((VehicleEntity) entity);
    }

    @Override
    public boolean locked(Entity entity) {
        return ((VehicleEntity) entity).getLocked();
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

    boolean available(VehicleEntity vehicle, UUID colony) {
        return accepts(vehicle) && !vehicle.getLocked() && owner(vehicle).filter(id -> !id.equals(colony)).isEmpty()
            && vehicle.getPassengers().stream().allMatch(passenger -> Bodies.of(passenger)
                .filter(body -> body.colonyId().filter(colony::equals).isPresent()).isPresent());
    }

    static boolean crewAllowed(Body body) {
        return body.licences().trade(Vocations.required(CREW)).filter(body.licences()::allows).isPresent();
    }

    @Override
    public Vehicles.Result board(Entity entity, Body body, int seat) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (!(vehicle.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
            || !crewAllowed(body) || body.colonyId().isEmpty() || !available(vehicle, body.colonyId().orElseThrow())
            || body.mob().level() != level || !body.mob().isAlive()
            || !WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(vehicle))
            || seat < 0 || seat >= vehicle.getMaxPassengers()
            || body.mob().isPassenger() && body.mob().getVehicle() != vehicle) {
            return Vehicles.Result.refused("unavailable");
        }
        if (body.mob().getVehicle() != vehicle && !VehicleSeatDuty.hasFooting(vehicle, body.mob())) {
            return Vehicles.Result.refused("destination");
        }
        Optional<UUID> previous = VehicleSeats.assigned(vehicle, seat);
        if (previous.isPresent() && !previous.orElseThrow().equals(body.id())) {
            // Swap only between two actual, authorized passengers of the same hull.
            Entity other = level.getEntity(previous.orElseThrow());
            int from = vehicle.getSeatIndex(body.mob());
            if (from < 0 || other == null || other.getVehicle() != vehicle
                || !VehicleSeats.holds(vehicle, body.mob())) {
                return Vehicles.Result.refused("occupied");
            }
            other.stopRiding();
            if (!vehicle.changeSeat(body.mob(), seat)) {
                other.startRiding(vehicle, true);
                vehicle.changeSeat(other, seat);
                return Vehicles.Result.refused("occupied");
            }
            other.startRiding(vehicle, true);
            vehicle.changeSeat(other, from);
            Bodies.of(other).ifPresent(otherBody -> VehicleSeats.assign(vehicle, otherBody, from));
        } else if (vehicle.getNthEntity(seat) != null && vehicle.getNthEntity(seat) != body.mob()) {
            return Vehicles.Result.refused("occupied");
        } else if (body.mob().getVehicle() == vehicle && vehicle.getSeatIndex(body.mob()) != seat
            && !vehicle.changeSeat(body.mob(), seat)) {
            return Vehicles.Result.refused("occupied");
        }
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
            .ifPresent(colony -> VehicleCrewCommands.board(colony, body.id(),
                vehicle.getSeat(seat).weapons().isEmpty() && seat != 0));
        body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id)).ifPresent(colony -> {
            VehicleProfile profile = profile(vehicle, colony);
            profile.equipment().ifPresent(equipment ->
                EquipmentProfiles.of(colony).assign(List.of(body.id()), Optional.of(equipment)));
        });
        VehicleSeats.assign(vehicle, body, seat);
        body.mob().setTarget(null);
        return Vehicles.Result.success();
    }

    static VehicleProfile profile(VehicleEntity vehicle, Colony colony) {
        var profiles = new VehicleProfiles(colony, vehicle.registryAccess());
        var type = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        VehicleProfile profile = profiles.assigned(vehicle).or(() -> profiles.defaultFor(type)).orElseGet(() -> {
            Set<Integer> required = INSTANCE.seats(vehicle).stream()
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
        return rules((VehicleEntity) entity);
    }

    static Map<String, WeaponRules.Rule> rules(VehicleEntity vehicle) {
        Map<String, WeaponRules.Rule> rules = new LinkedHashMap<>();
        VehicleSeats.owner(vehicle).flatMap(id -> Colonies.of(vehicle.getServer(), id))
            .ifPresent(colony -> rules.putAll(profile(vehicle, colony).rules()));
        rules.putAll(WeaponRules.read(vehicle.getPersistentData().getCompound(WeaponRules.TAG)));
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
                || !VehicleSeatDuty.hasFooting(vehicle, body.mob()))) {
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
            .filter(WarfareVehicles::crewAllowed)
            .filter(body -> !VehicleCrewCommands.commanded(colony, body.id()))
            .toList() : selected;
        Map<Body, Double> eta = new LinkedHashMap<>();
        people = people.stream().sorted(Comparator.comparingDouble(body ->
            body.mob().distanceToSqr(vehicle))).limit(32).toList();
        for (Body body : people) {
            if (!body.colonyId().filter(colony.id()::equals).isPresent()) {
                continue;
            }
            var approach = VehicleSeatDuty.approach(vehicle, body.mob());
            var destination = approach.map(VehicleSeatDuty.Approach::cell);
            Path path = approach.map(VehicleSeatDuty.Approach::path).orElse(null);
            if (path == null) {
                // Ground navigation is temporarily unavailable before landing; walking retries after assignment.
                if (!body.mob().onGround() && destination.isPresent()) {
                    double length = VehicleSeatDuty.world(vehicle, destination.orElseThrow())
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
                Vec3 next = VehicleSeatDuty.world(vehicle, path.getEntityPosAtNode(body.mob(), n));
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

    @Override
    public Vehicles.Result leave(Entity entity, int seat) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (vehicle.getLocked()) {
            return Vehicles.Result.refused("unavailable");
        }
        if (!VehicleController.atRest(vehicle)) {
            return Vehicles.Result.refused("airborne");
        }
        if (seat < -2 || seat >= vehicle.getMaxPassengers()) {
            return Vehicles.Result.refused("unavailable");
        }
        for (int n = 0; n < vehicle.getMaxPassengers(); n++) {
            if (seat == -2 ? n == 0 || !vehicle.getSeat(n).weapons().isEmpty() : seat != -1 && n != seat) {
                continue;
            }
            int index = n;
            vehicle.getPassengers().stream().filter(passenger -> vehicle.getSeatIndex(passenger) == index)
                .findFirst().filter(LivingEntity.class::isInstance)
                .map(LivingEntity.class::cast).ifPresent(body -> {
                    var at = vehicle.dismount(body);
                    body.stopRiding();
                    body.teleportTo(at.x, at.y, at.z);
                    if (body instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                });
            VehicleSeats.release(vehicle, n);
        }
        if (VehicleSeats.crew(vehicle).getAllKeys().stream().noneMatch(key -> key.startsWith("seat"))) {
            VehicleController.of(vehicle).cancel();
        }
        return Vehicles.Result.success();
    }

    @Override
    public Vehicles.Result order(Entity entity, Vehicles.Order order) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : VehicleController.of((VehicleEntity) entity).order(order);
    }

    @Override
    public Vehicles.Result appendPoint(Entity entity, WorldPos point, boolean attackMove) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : VehicleController.of((VehicleEntity) entity).append(point, attackMove);
    }

    @Override
    public Vehicles.Result cycle(Entity entity, int start) {
        return locked(entity) ? Vehicles.Result.refused("unavailable")
            : VehicleController.of((VehicleEntity) entity).cycle(start);
    }

    @Override
    public Vehicles.Result selectWeapon(Entity entity, int seat, int index) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (vehicle.getLocked() || seat < 0 || seat >= vehicle.getMaxPassengers() || index < 0
            || index >= vehicle.getSeat(seat).weapons().size()) {
            return Vehicles.Result.refused("unavailable");
        }
        vehicle.changeWeapon(seat, index, false);
        return Vehicles.Result.success();
    }

    @Override
    public Map<String, Component> weapons(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        var names = new LinkedHashMap<String, Component>();
        for (var seat : seats(entity)) {
            for (String weapon : seat.weapons()) {
                var data = vehicle.getGunData(weapon);
                String name = data == null ? null : data.get(GunProp.NAME);
                if (name == null || name.isBlank()) {
                    name = weapon;
                }
                names.put(seat.index() + "/" + weapon,
                    Component.translatableWithFallback("combat.vehicle.weapon." + name, "%s",
                    Component.translatableWithFallback(name, name)));
            }
        }
        return Map.copyOf(names);
    }

    @Override
    public Vehicles.Status status(Entity entity) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        Map<String, Integer> ammo = new LinkedHashMap<>();
        for (var seat : seats(vehicle)) {
            for (String weapon : seat.weapons()) {
                var data = vehicle.getGunData(weapon);
                int rounds = data == null ? 0 : data.ammo.get();
                if (data != null && data.useBackpackAmmo()) {
                    var ammunition = data.selectedAmmoConsumer().stack();
                    for (int slot = 0; slot < vehicle.getContainerSize(); slot++) {
                        if (ItemStack.isSameItemSameComponents(vehicle.getItem(slot), ammunition)) {
                            rounds += vehicle.getItem(slot).getCount();
                        }
                    }
                }
                ammo.put(seat.index() + "/" + weapon, rounds);
            }
        }
        return new Vehicles.Status(vehicle.getHealth(), vehicle.getMaxHealth(), vehicle.getEnergy(),
            vehicle.getMaxEnergy(), ammo, VehicleController.of(vehicle).action(),
            VehicleController.of(vehicle).route(), VehicleController.of(vehicle).target(),
            VehicleController.of(vehicle).firing(), DirectVehicleMotion.runway(vehicle));
    }

    public static boolean ownsAim(LivingEntity operator) {
        return operator.getVehicle() instanceof VehicleEntity vehicle && VehicleSeats.holds(vehicle, operator);
    }

    static void left(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof VehicleEntity vehicle && !vehicle.level().isClientSide()
            && vehicle.getRemovalReason() != null && vehicle.getRemovalReason().shouldDestroy()) {
            VehicleSeats.clear(vehicle);
        }
    }

    static void tick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof VehicleEntity wreck && !wreck.level().isClientSide() && wreck.isWreck()) {
            VehicleSeats.clear(wreck);
            return;
        }
        if (event.getEntity() instanceof VehicleEntity vehicle && !vehicle.level().isClientSide()
            && INSTANCE.accepts(vehicle) && VehicleSeats.owner(vehicle).isPresent()) {
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
                VehicleController.of(vehicle).cancel();
                return;
            }
            VehicleController.of(vehicle).tick();
            vehicle.getPassengers().stream().filter(Mob.class::isInstance).map(Mob.class::cast)
                .filter(mob -> VehicleSeats.holds(vehicle, mob))
                .forEach(mob -> mob.setTarget(null));
            VehicleGuns.of(vehicle).track();
        }
    }
}
