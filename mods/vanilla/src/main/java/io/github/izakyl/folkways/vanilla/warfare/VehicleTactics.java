package io.github.izakyl.folkways.vanilla.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.shell.warfare.VehicleController;
import io.github.izakyl.folkways.core.shell.warfare.VehicleGuns;
import io.github.izakyl.folkways.core.shell.warfare.VehicleSeats;
import io.github.izakyl.folkways.core.shell.warfare.WarfareAirfields;
import io.github.izakyl.folkways.core.shell.warfare.WarfareVehicles;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

final class VehicleTactics {
    private final VehicleEntity vehicle;
    private final VehicleController pilot;
    private final Map<Integer, Vehicles.Firing> firing = new HashMap<>();
    private Vehicles.Order firingOrder;
    private int idleTicks;

    VehicleTactics(VehicleEntity vehicle, VehicleController pilot) {
        this.vehicle = vehicle;
        this.pilot = pilot;
    }

    void tick() {
        fire();
        releaseIdleCrew();
    }

    static int nextPoint(Vehicles.Order order, int point, int size) {
        return point + 1 < size ? point + 1 : order.cycleStart();
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

    List<Vehicles.Firing> firing() {
        Map<Integer, Vehicles.Firing> current = firingOrder == pilot.currentOrder() ? firing : Map.of();
        return WarfareVehicles.provider().seats(vehicle).stream().filter(seat -> !seat.weapons().isEmpty())
            .map(seat -> current.getOrDefault(seat.index(),
                new Vehicles.Firing(seat.index(), Optional.empty(), false))).toList();
    }

    Vehicles.Result append(WorldPos next, boolean attackMove) {
        Vehicles.Order order = pilot.currentOrder();
        Vehicles.Action mode = attackMove ? Vehicles.Action.ATTACK_MOVE : Vehicles.Action.MOVE;
        boolean extending = order.action() == mode && order.cycleStart() < 0;
        var route = new ArrayList<>(extending ? order.points() : List.of());
        if (route.size() >= Vehicles.MAX_POINTS) {
            return Vehicles.Result.refused("destination");
        }
        int current = extending ? pilot.routeFinished() ? route.size() : pilot.routePoint() : 0;
        route.add(next);
        var result = order(new Vehicles.Order(mode, route, -1, Optional.empty(), Optional.empty()));
        if (result.accepted()) {
            pilot.routePoint(current);
        }
        return result;
    }

    Vehicles.Result cycle(int start) {
        Vehicles.Order order = pilot.currentOrder();
        if (start < 0 || start >= order.points().size() - 1 || order.cycleStart() >= 0) {
            return Vehicles.Result.refused("destination");
        }
        int current = pilot.routeFinished() ? start : pilot.routePoint();
        var result = order(new Vehicles.Order(order.action(), order.points(), start,
            order.target(), order.airport()));
        if (result.accepted()) {
            pilot.routePoint(current);
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
        if (!WarfareVehicles.provider().capabilities(vehicle).actions().contains(next.action())) {
            return Vehicles.Result.refused("unsupported");
        }
        if (next.action() == Vehicles.Action.DECOY) {
            idleTicks = 0;
            pilot.decoy();
            return Vehicles.Result.success();
        }
        if (next.action() == Vehicles.Action.SPRINT) {
            idleTicks = 0;
            pilot.toggleSprint();
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
        boolean airborne = WarfareAirfields.get().airborne(vehicle);
        boolean parked = pilot.parked(airborne);
        boolean alreadyDeparted = next.action() == Vehicles.Action.TAKE_OFF && airborne;
        // There is no Take off button: a flight order to a parked aircraft departs through its airport first.
        boolean automatic = parked && switch (next.action()) {
            case MOVE, PATROL, ATTACK_MOVE, LOITER -> true;
            default -> false;
        };
        boolean departs = next.action() == Vehicles.Action.TAKE_OFF && !alreadyDeparted || automatic;
        LandingSites.Site assigned = null;
        if (next.action() == Vehicles.Action.LAND || departs) {
            WarfareAirfields.get().home(vehicle);
            WarfareAirfields.get().release(vehicle);
            assigned = WarfareAirfields.get().assign(vehicle, departs, next.airport()).orElse(null);
            if (assigned == null && !(departs && vehicle.getEngineInfo() instanceof EngineInfo.Helicopter
                    && !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft))
                    && !(automatic && WarfareAirfields.get().offField(vehicle))) {
                pilot.reacquireAirfield();
                return Vehicles.Result.refused("landing_site");
            }
        } else {
            WarfareAirfields.get().release(vehicle);
        }
        pilot.command(next, route, next.action() == Vehicles.Action.LAND ? assigned : null,
            departs ? assigned : null);
        idleTicks = 0;
        if (moving && next.cycleStart() < 0 && !(vehicle.getEngineInfo() instanceof EngineInfo.Aircraft)) {
            pilot.reserve(route.getLast());
        }
        return Vehicles.Result.success();
    }

    private void releaseIdleCrew() {
        boolean idle = pilot.currentOrder().action() == Vehicles.Action.STOP
            || pilot.routeFinished() && pilot.holding() && pilot.target().isEmpty();
        if (!vehicle.getPersistentData().getBoolean(Vehicles.RELEASE_IDLE) || !idle || !VehicleController.atRest(vehicle)
            || pilot.inFlightManeuver()) {
            idleTicks = 0;
            return;
        }
        if (++idleTicks >= 200) {
            WarfareVehicles.provider().leave(vehicle, -1);
            idleTicks = 0;
        }
    }

    private void fire() {
        VehicleGuns.of(vehicle).clearAim();
        Vehicles.Order order = pilot.currentOrder();
        firingOrder = order;
        firing.clear();
        ServerLevel level = (ServerLevel) vehicle.level();
        var colony = VehicleSeats.owner(vehicle).flatMap(id -> Colonies.of(level.getServer(), id));
        var guns = VehicleGuns.of(vehicle);
        var rules = WarfareVehicles.provider().rules(vehicle);
        Entity ordered = order.target().map(level::getEntity).orElse(null);
        Set<Entity> engaged = new HashSet<>();
        for (var seat : WarfareVehicles.provider().seats(vehicle)) {
            Mob operator = VehicleSeats.occupant(vehicle, seat.index()).orElse(null);
            if (seat.weapons().isEmpty() || operator == null) {
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
                target = level.getEntitiesOfClass(Mob.class,
                    vehicle.getBoundingBox().inflate(data.get(GunProp.RANGE)),
                    foe -> Combatant.get().hostile(foe, operator))
                    .stream().filter(foe -> rule.permits(false, vehicle.distanceTo(foe), false))
                    .filter(foe -> guns.canAim(seat.index(), foe))
                    .filter(foe -> colony.filter(owner -> guns.planned(level, owner, seat.index(), foe)).isPresent())
                    .min(Comparator.<Mob, Boolean>comparing(engaged::contains)
                        .thenComparingDouble(vehicle::distanceToSqr)).orElse(null);
            }
            if (target != null && rule.permits(target == ordered, vehicle.distanceTo(target), false)
                && vehicle.distanceTo(target) <= data.get(GunProp.RANGE)) {
                engaged.add(target);
                Vehicles.Order expected = order;
                Entity aimed = target;
                boolean explicitlyOrdered = target == ordered;
                boolean shooting = VehicleGuns.of(vehicle).engage(seat.index(), operator, target,
                    () -> pilot.currentOrder() == expected && WarfareVehicles.provider().rules(vehicle)
                        .getOrDefault(seat.index() + "/" + weapon,
                            WeaponRules.DEFAULT)
                        .permits(explicitlyOrdered, vehicle.distanceTo(aimed), false));
                firing.put(seat.index(), new Vehicles.Firing(seat.index(),
                    Optional.of(Vehicles.Target.of(target)), shooting));
            }
        }
    }

}
