package io.github.izakyl.folkways.core.command.combat.net;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.Commands;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Targets;
import io.github.izakyl.folkways.core.combat.api.VehicleProfiles;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.command.combat.control.CombatSelectionOwnership;
import io.github.izakyl.folkways.core.command.combat.group.UnitGroups;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Authentication and all-or-reject validation precede integration mutations. */
public final class UnitOrders {
    private UnitOrders() {
    }

    public static UnitReply handle(ServerPlayer player, UnitRequest request) {
        Optional<Colony> allowed = SelectionOrders.authorized(player, request.colony());
        if (allowed.isEmpty()) {
            return new UnitReply(request.request(), request.colony(), Vehicles.Result.refused("unauthorized"),
                Optional.empty());
        }
        Colony colony = allowed.orElseThrow();
        Vehicles.Result result;
        try {
            result = apply(player, colony, request);
        } catch (IllegalArgumentException | IllegalStateException | java.util.NoSuchElementException rejected) {
            result = Vehicles.Result.refused("unavailable");
        }
        return new UnitReply(request.request(), colony.id(), result, Optional.of(snapshot(player, colony)));
    }

    private static Vehicles.Result apply(ServerPlayer player, Colony colony, UnitRequest request) {
        if (request.operation() == UnitRequest.Operation.ROSTER) {
            return Vehicles.Result.success();
        }
        var profiles = new VehicleProfiles(colony, player.registryAccess());
        if (request.operation() == UnitRequest.Operation.PROFILE_SAVE) {
            var profile = request.profile().orElseThrow();
            profile.equipment().ifPresent(id ->
                io.github.izakyl.folkways.core.equipment.api.EquipmentProfiles.of(colony).get(id).orElseThrow());
            profiles.replace(profile);
            return Vehicles.Result.success();
        }
        if (request.operation() == UnitRequest.Operation.PROFILE_DELETE) {
            profiles.delete(request.unit());
            return Vehicles.Result.success();
        }
        if (request.operation() == UnitRequest.Operation.FACILITY_RUN
            || request.operation() == UnitRequest.Operation.FACILITY_TOGGLE
            || request.operation() == UnitRequest.Operation.FACILITY_TARGET
            || request.operation() == UnitRequest.Operation.FACILITY_PARAMETER
            || request.operation() == UnitRequest.Operation.FACILITY_CHOICE) {
            return FacilityOrders.apply(player, colony, request);
        }
        var group = UnitGroups.of(colony).group(request.unit());
        if (request.operation() == UnitRequest.Operation.RULE && group.isPresent()) {
            var squad = group.orElseThrow();
            if (squad.members().stream().anyMatch(id -> !CombatSelectionOwnership.of(colony).available(player, id))) {
                return Vehicles.Result.refused("unauthorized");
            }
            squad.weaponRule(request.weapon(), request.rule());
            return Vehicles.Result.success();
        }
        Entity unit = player.serverLevel().getEntity(request.unit());
        if (unit == null || !available(player, colony, unit)) {
            return Vehicles.Result.refused("unavailable");
        }
        Vehicles.Provider provider = Vehicles.of(unit).orElseThrow();
        if (request.operation() == UnitRequest.Operation.PROFILE_BIND) {
            if (request.residents().size() != 1) {
                return Vehicles.Result.refused("unavailable");
            }
            profiles.bind(unit, request.residents().getFirst(), provider.type(unit));
            return Vehicles.Result.success();
        }
        List<Body> selected = request.residents().stream().distinct().map(id -> {
            Body body = Optional.ofNullable(player.serverLevel().getEntity(id)).flatMap(Bodies::of).orElseThrow();
            if (!body.colonyId().filter(colony.id()::equals).isPresent() || colony.claimOf(id).isEmpty()
                || !Commands.commandable(body) || !CombatSelectionOwnership.of(colony).available(player, id)
                || !WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(unit))) {
                throw new IllegalArgumentException("unavailable resident");
            }
            return body;
        }).toList();
        return switch (request.operation()) {
            case BOARD -> provider.boardAvailable(unit, colony, selected);
            case ASSIGN -> selected.size() == 1 ? provider.board(unit, selected.getFirst(), request.seat())
                : Vehicles.Result.refused("no_crew");
            case LEAVE -> provider.leave(unit, request.seat());
            case RELEASE_OWNERSHIP -> provider.owner(unit).filter(colony.id()::equals).isPresent()
                ? provider.releaseOwnership(unit) : Vehicles.Result.refused("unauthorized");
            case RELEASE_IDLE -> {
                if (request.index() == 1) {
                    unit.getPersistentData().putBoolean(Vehicles.RELEASE_IDLE, true);
                } else {
                    unit.getPersistentData().remove(Vehicles.RELEASE_IDLE);
                }
                yield Vehicles.Result.success();
            }
            case ALTITUDE -> {
                if (!provider.capabilities(unit).flying() || request.index() < 0
                    || request.index() >= Vehicles.Altitude.values().length) {
                    yield Vehicles.Result.refused("unsupported");
                }
                Vehicles.altitude(unit, Vehicles.Altitude.values()[request.index()]);
                yield Vehicles.Result.success();
            }
            case ORDER, APPEND, CYCLE -> {
                if (request.operation() == UnitRequest.Operation.APPEND && request.order().points().size() != 1) {
                    yield Vehicles.Result.refused("destination");
                }
                if (request.operation() == UnitRequest.Operation.CYCLE && request.index() < 0) {
                    yield Vehicles.Result.refused("destination");
                }
                // Commands reach anywhere the unit and its destination are loaded, however far from the player.
                if (request.order().points().stream().anyMatch(point -> !point.sameRealm(WorldSpaces.at(unit))
                    || WorldSpaces.world(player.level(), point)
                        .filter(pos -> player.level().hasChunkAt(BlockPos.containing(pos))).isEmpty())) {
                    yield Vehicles.Result.refused("destination");
                }
                Entity target = request.order().target().map(player.serverLevel()::getEntity).orElse(null);
                LivingEntity operator = unit.getPassengers().stream().filter(LivingEntity.class::isInstance)
                    .map(LivingEntity.class::cast).findFirst().orElse(null);
                if (request.order().target().isPresent()) {
                    if (target == null || operator == null
                        || !Targets.explicitlyAttackable(target, operator, player)) {
                        yield Vehicles.Result.refused("target");
                    }
                }
                var result = UnitGroups.of(colony).commandVehicle(player, unit, request.order(),
                    request.operation() == UnitRequest.Operation.APPEND,
                    request.operation() == UnitRequest.Operation.CYCLE ? request.index() : -1);
                if (result.accepted() && target != null && request.order().action() == Vehicles.Action.ATTACK) {
                    FocusMark.attack(player, colony, target, operator);
                }
                yield result;
            }
            case WEAPON -> provider.selectWeapon(unit, request.seat(), request.index());
            case RULE -> {
                if (provider.seats(unit).stream().noneMatch(seat -> seat.weapons().stream()
                    .anyMatch(weapon -> request.weapon().equals(seat.index() + "/" + weapon)))) {
                    yield Vehicles.Result.refused("unavailable");
                }
                var rules = new LinkedHashMap<>(WeaponRules.read(
                    unit.getPersistentData().getCompound(WeaponRules.TAG)));
                rules.put(request.weapon(), request.rule());
                unit.getPersistentData().put(WeaponRules.TAG, WeaponRules.write(rules));
                yield Vehicles.Result.success();
            }
            case ROSTER -> Vehicles.Result.success();
            case PROFILE_SAVE, PROFILE_DELETE, PROFILE_BIND, FACILITY_RUN, FACILITY_TOGGLE, FACILITY_TARGET,
                FACILITY_PARAMETER, FACILITY_CHOICE ->
                Vehicles.Result.refused("unavailable");
        };
    }

    public static boolean available(ServerPlayer player, Colony colony, Entity entity) {
        return entity.isAlive() && CombatSelectionOwnership.of(colony).available(player, entity.getUUID())
            && entity.level() == player.level()
            && WorldSpaces.at(entity).sameRealm(WorldSpaces.at(player))
            && Vehicles.availableTo(player, colony.id(), entity)
            && entity.getPassengers().stream().allMatch(passenger -> Bodies.of(passenger)
                .filter(body -> body.colonyId().filter(colony.id()::equals).isPresent()
                    && CombatSelectionOwnership.of(colony).available(player, body.id())).isPresent());
    }

    private static Map<String, WeaponRules.Rule> vehicleRules(Vehicles.Provider provider, Entity entity) {
        var rules = new LinkedHashMap<String, WeaponRules.Rule>();
        provider.weapons(entity).keySet().forEach(weapon -> rules.put(weapon, WeaponRules.DEFAULT));
        rules.putAll(provider.rules(entity));
        return Map.copyOf(rules);
    }

    public static UnitSnapshot snapshot(ServerPlayer player, Colony colony) {
        List<UnitSnapshot.Unit> units = new ArrayList<>();
        List<UnitSnapshot.Resident> residents = new ArrayList<>();
        for (var resident : colony.residents()) {
            Optional.ofNullable(player.serverLevel().getEntity(resident.id())).flatMap(Bodies::of)
                .filter(Commands::commandable)
                .filter(body -> WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(player))).ifPresent(body ->
                    residents.add(new UnitSnapshot.Resident(body.id(), body.mob().getDisplayName(),
                        CombatSelectionOwnership.of(colony).reserved(body.id()),
                        Optional.ofNullable(body.mob().getVehicle()).filter(entity -> Vehicles.of(entity).isPresent())
                            .map(Entity::getUUID))));
        }
        residents.stream().map(resident -> UnitGroups.of(colony).ofResident(resident.id()))
            .flatMap(Optional::stream).distinct()
            .filter(group -> group.members().stream().anyMatch(id -> residents.stream()
                .anyMatch(resident -> resident.id().equals(id) && resident.vehicle().isEmpty())))
            .forEach(group -> {
                var people = group.members().stream().map(id -> player.serverLevel().getEntity(id))
                    .filter(Objects::nonNull).flatMap(entity -> Bodies.of(entity).stream()).toList();
                var position = people.isEmpty() ? WorldSpaces.at(player) : WorldSpaces.at(people.getFirst().mob());
                var weapons = new LinkedHashMap<String, Component>();
                people.forEach(body -> weapons.putAll(Combatant.get()
                    .weapons(body)));
                var rules = new LinkedHashMap<String, WeaponRules.Rule>();
                weapons.keySet().forEach(weapon -> rules.put(weapon, WeaponRules.DEFAULT));
                rules.putAll(group.weaponRules());
                units.add(new UnitSnapshot.Unit(group.id(), UnitSnapshot.Kind.SQUAD,
                    Component.translatable("combat.unit.squad"), position, Optional.of(colony.id()),
                    List.copyOf(group.members()), List.of(), new Vehicles.Capabilities(true, Vehicles.Airframe.GROUND, true,
                        List.of()),
                    new Vehicles.Status(people.stream().mapToDouble(body -> body.mob().getHealth()).sum(),
                        people.stream().mapToDouble(body -> body.mob().getMaxHealth()).sum(),
                        0, 0, Map.of(), "folkways.rts.phase."
                            + group.phase().name().toLowerCase(java.util.Locale.ROOT)), weapons, rules, Optional.empty(), false,
                        Vehicles.Altitude.DEFAULT));
            });
        // Owned hulls remain available at distance; unowned hulls are restricted to actual entity tracking.
        for (Entity entity : player.serverLevel().getAllEntities()) {
            if (Vehicles.of(entity).isEmpty() || !available(player, colony, entity)) {
                continue;
            }
            var provider = Vehicles.of(entity).orElseThrow();
            units.add(new UnitSnapshot.Unit(entity.getUUID(), UnitSnapshot.Kind.VEHICLE, entity.getDisplayName(),
                WorldSpaces.at(entity), provider.owner(entity), List.of(), provider.seats(entity),
                provider.capabilities(entity), provider.status(entity), provider.weapons(entity),
                vehicleRules(provider, entity), provider.readiness(entity, colony),
                entity.getPersistentData().getBoolean(Vehicles.RELEASE_IDLE), Vehicles.altitude(entity),
                DefinedFacilities.of(colony).flatMap(facilities -> facilities.facility(entity.getUUID()).map(facility ->
                    new UnitSnapshot.FacilityUnit(facility.extent(), facility.card(), facilities.triggerStates(facility.name()),
                        Map.of())))));
        }
        units.addAll(FacilityOrders.units(player, colony));
        return new UnitSnapshot(colony.id(), units.stream().limit(512).toList(),
            residents.stream().limit(CombatPackets.MAX_MEMBERS).toList(), LandingSites.in(player.serverLevel())
                .stream().filter(site -> site.airport().colony().filter(id -> !id.equals(colony.id())).isEmpty())
                .filter(site -> site.pose(player.serverLevel()).isPresent()).limit(512).toList(),
            new VehicleProfiles(colony, player.registryAccess()).all());
    }
}
