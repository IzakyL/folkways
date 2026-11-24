package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.data.gun.GunProp;
import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public class WarfareVehicles implements Vehicles.Provider {
    private static WarfareVehicles provider = new WarfareVehicles();

    public static WarfareVehicles provider() {
        return provider;
    }

    public static void gameplay(WarfareVehicles value) {
        provider = java.util.Objects.requireNonNull(value);
    }

    protected WarfareVehicles() {
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
    protected static Vehicles.Airframe airframe(VehicleEntity vehicle) {
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

    protected boolean available(VehicleEntity vehicle, UUID colony) {
        return accepts(vehicle) && !vehicle.getLocked() && owner(vehicle).filter(id -> !id.equals(colony)).isEmpty();
    }

    @Override
    public Vehicles.Result board(Entity entity, Body body, int seat) {
        VehicleEntity vehicle = (VehicleEntity) entity;
        if (!(vehicle.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
            || !mayBoard(body) || body.colonyId().isEmpty() || !available(vehicle, body.colonyId().orElseThrow())
            || body.mob().level() != level || !body.mob().isAlive()
            || !WorldSpaces.at(body.mob()).sameRealm(WorldSpaces.at(vehicle))
            || seat < 0 || seat >= vehicle.getMaxPassengers()
            || body.mob().isPassenger() && body.mob().getVehicle() != vehicle) {
            return Vehicles.Result.refused("unavailable");
        }
        if (body.mob().getVehicle() != vehicle && !VehicleBoarding.hasFooting(vehicle, body.mob())) {
            return Vehicles.Result.refused("destination");
        }
        Optional<UUID> previous = VehicleSeats.assigned(vehicle, seat);
        if (previous.isPresent() && !previous.orElseThrow().equals(body.id())) {
            Entity other = level.getEntity(previous.orElseThrow());
            int from = vehicle.getSeatIndex(body.mob());
            if (other == null || !maySwap(vehicle, body, other, from)) {
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
        boarding(level, vehicle, body, seat);
        VehicleSeats.assign(vehicle, body, seat);
        body.mob().setTarget(null);
        return Vehicles.Result.success();
    }

    protected boolean maySwap(VehicleEntity vehicle, Body body, Entity other, int from) {
        return false;
    }

    protected boolean mayBoard(Body body) {
        return true;
    }

    protected void boarding(ServerLevel level, VehicleEntity vehicle, Body body, int seat) {
    }

    @Override
    public Map<String, WeaponRules.Rule> rules(Entity entity) {
        return WeaponRules.read(entity.getPersistentData().getCompound(WeaponRules.TAG));
    }

    @Override
    public Vehicles.Result leave(Entity entity, int seat) {
        VehicleEntity vehicle = (VehicleEntity) entity;
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
        return Vehicles.Result.refused("unsupported");
    }

    protected Vehicles.Result destination(VehicleEntity vehicle, WorldPos destination) {
        return Vehicles.Result.refused("unsupported");
    }

    protected void routineStarted(Colony colony, ServerPlayer player, VehicleEntity vehicle, String routine) {
    }

    protected void tickCrew(VehicleEntity vehicle, VehicleController pilot) {
    }

    protected void forget(VehicleController pilot) {
    }

    protected List<Vehicles.Firing> firing(VehicleEntity vehicle) {
        return List.of();
    }

    protected VehicleTraffic traffic(VehicleEntity vehicle) {
        return VehicleTraffic.NONE;
    }

    protected double height(VehicleEntity vehicle, boolean fixedWing, double floor) {
        return floor;
    }

    protected double sprintFactor(VehicleEntity vehicle) {
        return 1;
    }

    protected int nextPoint(Vehicles.Order order, int point, int count) {
        return -1;
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
            firing(vehicle), DirectVehicleMotion.runway(vehicle));
    }

    // A press of an emplacement's trigger binds its own queued shot; otherwise a mobile crew's gun may.
    public Runnable queuedShot(VehicleEntity vehicle, Runnable nativeShot) {
        return WarfareTrigger.queued(vehicle, nativeShot).orElseGet(() -> VehicleGuns.queued(vehicle, nativeShot));
    }

    public boolean permitsShot(VehicleEntity vehicle, ShootParameters parameters) {
        return WarfareTrigger.emitting(vehicle, parameters) || VehicleGuns.permits(vehicle, parameters);
    }

    public static boolean ownsAim(LivingEntity operator) {
        return operator.getVehicle() instanceof VehicleEntity vehicle && VehicleSeats.holds(vehicle, operator);
    }

    static void left(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            VehicleGuns.remove(event.getEntity());
            VehicleController.remove(event.getEntity());
        }
        if (event.getEntity() instanceof VehicleEntity vehicle && !vehicle.level().isClientSide()
            && vehicle.getRemovalReason() != null && vehicle.getRemovalReason().shouldDestroy()) {
            VehicleSeats.clear(vehicle);
        }
    }

    public static void tick(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof VehicleEntity wreck && !wreck.level().isClientSide() && wreck.isWreck()) {
            VehicleSeats.clear(wreck);
            return;
        }
        if (event.getEntity() instanceof VehicleEntity vehicle && !vehicle.level().isClientSide()
            && provider().accepts(vehicle) && VehicleSeats.owner(vehicle).isPresent()) {
            VehicleController.of(vehicle).tick();
            vehicle.getPassengers().stream().filter(Mob.class::isInstance).map(Mob.class::cast)
                .filter(mob -> VehicleSeats.holds(vehicle, mob))
                .forEach(mob -> mob.setTarget(null));
            VehicleGuns.of(vehicle).track();
        }
    }
}
