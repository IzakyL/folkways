package io.github.izakyl.folkways.firearms.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.combat.api.VehicleProfile;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Ordinary fetch planning supplies declared needs; the receipt inserts only at a stationary hull. */
final class VehicleSupply {
    private record Key(UUID hull, int entry) {
    }

    private final Colony colony;
    private final Sweep sweep;
    private final Map<Key, Node> pending = new HashMap<>();
    private final Set<Key> refilling = new HashSet<>();

    VehicleSupply(Colony colony) {
        this.colony = colony;
        sweep = new Sweep(colony, WarfareVehicles.ID, 20,
            (id, how) -> pending.values().removeIf(node -> node.id().equals(id)));
    }

    void tick(MinecraftServer server) {
        sweep.tick(server, this::prune, this::goals);
    }

    private void prune(MinecraftServer server) {
        Set<UUID> present = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof VehicleEntity hull && hull.isAlive() && !hull.isWreck()
                    && VehicleSeats.owner(hull).filter(colony.id()::equals).isPresent()) {
                    present.add(hull.getUUID());
                }
            }
        }
        pending.keySet().removeIf(key -> !present.contains(key.hull()));
        refilling.removeIf(key -> !present.contains(key.hull()));
    }

    static int count(VehicleEntity hull, VehicleProfile.Stock entry) {
        int count = 0;
        for (int slot = 0; slot < hull.getContainerSize(); slot++) {
            if (Goods.matches(hull.getItem(slot), entry.items())) {
                count += hull.getItem(slot).getCount();
            }
        }
        return count;
    }

    private boolean available(VehicleEntity hull, ColonyView view) {
        if (!hull.isAlive() || hull.isWreck() || hull.getLocked() || !hull.onGround()
            || hull.getDeltaMovement().lengthSqr() > .0001
            || !VehicleSeats.owner(hull).filter(colony.id()::equals).isPresent()
            || !VehicleController.of(hull).idle()) {
            return false;
        }
        var cells = view.claims().stream().flatMap(claim -> claim.what().cells().stream())
            .filter(cell -> cell.sameRealm(WorldSpaces.at(hull)))
            .flatMap(cell -> WorldSpaces.world(view.level(), cell).stream()).toList();
        if (cells.isEmpty()) {
            return false;
        }
        // Colonies claim sites rather than territory: their registered site's bounding footprint is the depot area.
        double x = hull.getX();
        double z = hull.getZ();
        return x >= cells.stream().mapToDouble(pos -> Math.floor(pos.x)).min().orElseThrow()
            && x < cells.stream().mapToDouble(pos -> Math.floor(pos.x) + 1).max().orElseThrow()
            && z >= cells.stream().mapToDouble(pos -> Math.floor(pos.z)).min().orElseThrow()
            && z < cells.stream().mapToDouble(pos -> Math.floor(pos.z) + 1).max().orElseThrow();
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        pending.entrySet().removeIf(entry -> view.level().getEntity(entry.getKey().hull()) != null
            && !entry.getValue().ready(view.level()));
        for (var entity : view.level().getAllEntities()) {
            if (!(entity instanceof VehicleEntity hull) || !available(hull, view)) {
                continue;
            }
            VehicleProfile profile = WarfareVehicles.profile(hull, colony);
            for (int index = 0; index < profile.stock().size(); index++) {
                VehicleProfile.Stock entry = profile.stock().get(index);
                Key key = new Key(hull.getUUID(), index);
                int current = count(hull, entry);
                if (current >= entry.target()) {
                    refilling.remove(key);
                    pending.remove(key);
                    continue;
                }
                if (current < entry.threshold()) {
                    refilling.add(key);
                }
                if (refilling.contains(key)) {
                    Node node = pending.get(key);
                    if (node == null) {
                        node = receipt(hull, profile, entry, view, false).orElse(null);
                        if (node != null) {
                            pending.put(key, node);
                        }
                    }
                    if (node != null) {
                        goals.add(Grown.of(node));
                    }
                }
            }
            if (hull.hasEnergyStorage() && hull.getEnergy() < Math.min(profile.energy(), hull.getMaxEnergy())) {
                Key key = new Key(hull.getUUID(), -1);
                var battery = colony.inventory().snapshot().stores().values().stream()
                    .filter(store -> store.dimension().equals(view.dimension()))
                    .flatMap(store -> store.slots().stream()).map(slot -> slot.stackCopy())
                    .filter(stack -> {
                        var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
                        return energy != null && energy.extractEnergy(Integer.MAX_VALUE, true) > 0;
                    }).findFirst();
                if (!pending.containsKey(key)) {
                    battery.flatMap(stack -> receipt(hull, profile,
                        new VehicleProfile.Stock(ItemSpec.exact(stack), 1, 1), view, true))
                        .ifPresent(node -> pending.put(key, node));
                }
                Optional.ofNullable(pending.get(key)).ifPresent(node -> goals.add(Grown.of(node)));
            } else {
                pending.remove(new Key(hull.getUUID(), -1));
            }
        }
        return goals;
    }

    Optional<Node> receipt(VehicleEntity hull, VehicleProfile profile, VehicleProfile.Stock entry,
                                   ColonyView view, boolean energy) {
        var destination = hull.position();
        var stances = Stances.of(hull.level(), VehicleSeatDuty.footings(hull));
        if (stances.isEmpty()) {
            return Optional.empty();
        }
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), WarfareVehicles.ID, WorkSite.on(hull),
            stances.orElseThrow(), Workload.Once.of(1))
            .needs(new Need(entry.items(), energy ? 1 : entry.target() - count(hull, entry), true)).done();
        return Optional.of(new Node() {
            @Override
            public NodeSpec spec() {
                return spec;
            }

            @Override
            public boolean permitsProduction(io.github.izakyl.folkways.core.item.api.ItemSpec input) {
                return false;
            }

            @Override
            public boolean ready(ServerLevel level) {
                return hull.level() == level && available(hull, view)
                    && hull.position().distanceToSqr(destination) < 4
                    && WarfareVehicles.profile(hull, colony).equals(profile);
            }

            @Override
            public Outcome commit(ServerLevel level, Worker who) {
                List<ItemStack> returned = new ArrayList<>();
                int missing = ready(level) ? entry.target() - count(hull, entry) : 0;
                for (ItemStack supplied : who.supplied()) {
                    ItemStack rest = supplied.copy();
                    if (energy && ready(level)) {
                        var battery = rest.getCapability(Capabilities.EnergyStorage.ITEM);
                        if (battery != null) {
                            int wanted = (int) Math.max(0,
                                Math.min(profile.energy(), hull.getMaxEnergy()) - hull.getEnergy());
                            hull.setEnergy(hull.getEnergy() + battery.extractEnergy(wanted, false));
                        }
                    } else if (!energy && Goods.matches(rest, entry.items())) {
                        for (int slot = 0; slot < hull.getContainerSize() && missing > 0 && !rest.isEmpty(); slot++) {
                            ItemStack held = hull.getItem(slot);
                            if (!hull.canPlaceItem(slot, rest) || !held.isEmpty()
                                && !ItemStack.isSameItemSameComponents(held, rest)) {
                                continue;
                            }
                            int room = Math.min(rest.getMaxStackSize(), hull.getMaxStackSize()) - held.getCount();
                            int take = Math.min(Math.min(room, rest.getCount()), missing);
                            if (take > 0) {
                                hull.setItem(slot, rest.copyWithCount(held.getCount() + take));
                                rest.shrink(take);
                                missing -= take;
                            }
                        }
                    }
                    if (!rest.isEmpty()) {
                        returned.add(rest);
                    }
                }
                hull.setChanged();
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), returned);
            }
        });
    }
}
