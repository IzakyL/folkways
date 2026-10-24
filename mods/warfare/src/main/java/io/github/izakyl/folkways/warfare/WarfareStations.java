package io.github.izakyl.folkways.warfare;

import com.atsuishio.superbwarfare.data.gun.ShootParameters;
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineType;
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType;
import com.atsuishio.superbwarfare.entity.vehicle.Fh77bwEntity;
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Urge;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.command.ResidentCommands;
import io.github.izakyl.folkways.plugins.combat.weapon.PatrolSupplies;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

public final class WarfareStations {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "warfare/stations");
    private static final Map<VehicleEntity, StationNode> ACTIVE = new IdentityHashMap<>();

    private WarfareStations() { }

    static void declare(Declaring declaring) {
        io.github.izakyl.folkways.core.api.participation.Participations.register(
            io.github.izakyl.folkways.plugins.person.PersonBody.ID,
            io.github.izakyl.folkways.core.api.participation.Stake.urge(ID),
            io.github.izakyl.folkways.core.api.participation.Participation.ALWAYS);
        PatrolSupplies.registerSupply(ID, WarfareStations::supplies);
        declaring.colony(ID, scope -> {
            scope.urges((who, view) -> urges(scope.colony(), who));
            scope.tick(server -> List.copyOf(ACTIVE.values()).stream().filter(node -> node.colony() == scope.colony())
                .filter(node -> !node.valid()).forEach(StationNode::close));
            scope.closed(why -> List.copyOf(ACTIVE.values()).stream().filter(node -> node.colony() == scope.colony()).forEach(StationNode::close));
        });
    }

    public static boolean emplacement(VehicleEntity vehicle) {
        return vehicle instanceof Fh77bwEntity || vehicle.computed().getEngineType() == EngineType.FIXED
            && (vehicle.getVehicleType() == VehicleType.ARTILLERY || vehicle.getVehicleType() == VehicleType.DEFENSE);
    }

    /** Guard posts use the same twelve-block operating area as Vanilla's guard leash. */
    public static Optional<VehicleEntity> find(Colony colony, Body body) {
        if (!(body.mob() instanceof ResidentEntity person) || !(person.level() instanceof ServerLevel level)
                || person.licences().of(Vocations.required(PatrolContent.VOCATION)).isEmpty()) { return Optional.empty(); }
        var guard = ResidentCommands.of(colony).current(body.id()).filter(order -> order.mode() == ResidentCommands.Mode.GUARD);
        if (guard.isEmpty()) { return Optional.empty(); }
        var point = guard.get().destination();
        var center = WorldSpaces.world(level, point).orElse(null);
        if (center == null) { return Optional.empty(); }
        return level.getEntitiesOfClass(VehicleEntity.class, new AABB(center, center).inflate(12), vehicle ->
            emplacement(vehicle) && !vehicle.isWreck() && !vehicle.isRemoved() && !vehicle.getLocked()
                && vehicle.distanceToSqr(center) <= 12 * 12 && WorldSpaces.at(vehicle).sameRealm(point)
                && vehicle.getDeltaMovement().horizontalDistanceSqr() < .001
                && vehicle.getPassengers().stream().allMatch(passenger -> passenger == person)
                && (!ACTIVE.containsKey(vehicle) || ACTIVE.get(vehicle).body() == person))
            .stream().filter(vehicle -> weaponName(vehicle) != null)
            .sorted(Comparator.comparingDouble((VehicleEntity vehicle) -> vehicle.distanceToSqr(person))
                .thenComparing(vehicle -> vehicle.getUUID().toString())).findFirst();
    }

    static String weaponName(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity) { return "Main"; }
        return vehicle.getGunName(vehicle.getTurretControllerIndex());
    }

    static ItemSpec ammunition(VehicleEntity vehicle) {
        if (vehicle instanceof MortarEntity && !vehicle.getItem(0).isEmpty()) { return ItemSpec.exact(vehicle.getItem(0)); }
        var data = vehicle.getGunData(weaponName(vehicle));
        if (data == null) { return null; }
        ItemStack stack = data.selectedAmmoConsumer().stack();
        return stack.isEmpty() ? null : ItemSpec.exact(stack);
    }

    static List<Amount> supplies(Colony colony, Body body) {
        return find(colony, body).map(WarfareStations::ammunition).map(ammo -> List.of(new Amount(ammo, 2))).orElse(List.of());
    }

    static List<Urge> urges(Colony colony, Worker who) {
        if (!(who.body() instanceof ResidentEntity body) || body.getHealth() <= body.getMaxHealth() * .35F) { return List.of(); }
        var found = find(colony, body);
        if (found.isEmpty()) { return List.of(); }
        VehicleEntity station = found.get();
        ItemSpec ammo = ammunition(station);
        boolean stocked = station instanceof MortarEntity ? !station.getItem(0).isEmpty()
            : station.getGunData(weaponName(station)).hasEnoughAmmoToShoot(station.getAmmoSupplier());
        if (!stocked && (ammo == null || Goods.countIn(who.pack(), ammo) == 0)) { return List.of(); }
        var order = ResidentCommands.of(colony).current(body.id()).orElseThrow();
        var cells = Reach.workableCells((ServerLevel) body.level(), station.blockPosition()).stream()
            .filter(cell -> cell.distSqr(station.blockPosition()) <= 3 * 3).toList();
        Optional<Stances> stances = Stances.of((ServerLevel) body.level(), cells);
        if (stances.isEmpty()) { return List.of(); }
        return List.of(new Urge(ResourceLocation.fromNamespaceAndPath("folkways", "warfare/station/" + order.id() + "/" + station.getUUID()),
            Urge.DIRE + .75, true, () -> new StationNode(colony, body, station, order, stances.get())));
    }

    static boolean claim(VehicleEntity station, StationNode node) {
        StationNode previous = ACTIVE.putIfAbsent(station, node);
        return previous == null || previous == node;
    }
    static void release(VehicleEntity station, StationNode node) { ACTIVE.remove(station, node); }

    /** SBW queues vehicle discharges; check the active duty again at the actual native discharge. */
    public static boolean permits(ShootParameters parameters) {
        if (!(parameters.ammoSupplier instanceof VehicleEntity station) || !(parameters.shooter instanceof ResidentEntity)) { return true; }
        StationNode node = ACTIVE.get(station);
        return node != null && node.body() == parameters.shooter && node.valid() && node.safe();
    }

    public static boolean permitsMortar(MortarEntity mortar) {
        StationNode node = ACTIVE.get(mortar);
        return node == null || node.valid() && node.safe();
    }

    static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) { return; }
        List.copyOf(ACTIVE.values()).stream().filter(node -> node.body() == event.getEntity() || node.station() == event.getEntity())
            .forEach(StationNode::close);
    }
}
