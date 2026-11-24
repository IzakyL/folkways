package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.facility.api.ContainerFacility;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Dining zone cabinets and their per-slot drink requests. */
final class DiningSupplies {
    private final Colony colony;
    private final java.util.random.RandomGenerator random;
    private volatile List<Cabinet> cabinets = List.of();
    private Map<Zone, Set<WorldPos>> bodies = Map.of();
    private final Map<WorldPos, List<ItemStack>> stock = new ConcurrentHashMap<>();
    private final Map<Slot, ItemSpec> refills = new ConcurrentHashMap<>();
    private final Map<Slot, Map<ItemSpec, Long>> refused = new ConcurrentHashMap<>();
    record Cabinet(ServerLevel level, UUID zone, BlockPos at, ItemSpec menu, List<BlockPos> approaches,
                   List<Dining.Seat> seats) { }
    private record Slot(WorldPos at, UUID zone, ItemSpec menu, int slot) { }
    private record Zone(ServerLevel level, UUID id) { }

    DiningSupplies(Colony colony, java.util.random.RandomGenerator random) { this.colony = colony; this.random = random; }

    void refresh(MinecraftServer server) {
        var found = new ArrayList<Cabinet>();
        var seen = new HashSet<WorldPos>();
        var owned = new HashMap<Zone, Set<WorldPos>>();
        for (var view : colony.views(server)) {
            var level = view.level();
            for (var zone : Fronts.of(colony).zonesIn(level)) {
                if (!zone.delegation().equals(LivingContent.DINING)) {
                    continue;
                }
                var blocks = cabinetBlocks(level, zone);
                if (!blocks.isEmpty()) {
                    owned.put(new Zone(level, zone.id()), cabinetCells(level, blocks));
                }
                var menu = zone.settings().items(LivingContent.DINING_DRINK.key());
                if (menu.isEmpty()) {
                    continue;
                }
                for (BlockPos at : blocks) {
                    if (seen.add(WorldPos.of(level, at))) {
                        findCabinet(level, zone, at, menu.get()).ifPresent(found::add);
                    }
                }
            }
        }
        updateBodies(owned);
        cabinets = List.copyOf(found);
        stock.keySet().retainAll(seen);
        refills.keySet().removeIf(slot -> !hasCabinet(slot));
        refused.keySet().removeIf(slot -> !hasCabinet(slot));
    }

    private boolean hasCabinet(Slot slot) {
        return cabinets.stream().anyMatch(cabinet -> slot.at.equals(WorldPos.of(cabinet.level, cabinet.at))
            && slot.zone.equals(cabinet.zone) && slot.menu.equals(cabinet.menu));
    }

    private static Optional<Cabinet> findCabinet(ServerLevel level, ZoneView zone, BlockPos at, ItemSpec menu) {
        var approaches = Direction.Plane.HORIZONTAL.stream().map(at::relative)
            .filter(p -> level.isLoaded(p) && Footing.withFeetAt(level, p) && level.getFluidState(p).isEmpty()).toList();
        if (approaches.isEmpty()) { return Optional.empty(); }
        List<Dining.Seat> seats = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos chair = at.relative(side);
            if (!includes(zone, chair) || !DiningTables.chair(level, chair)) { continue; }
            for (Direction aisle : Direction.Plane.HORIZONTAL) {
                BlockPos approach = chair.relative(aisle);
                if (!approach.equals(at) && level.isLoaded(approach) && Footing.withFeetAt(level, approach)
                        && level.getFluidState(approach).isEmpty()) {
                    seats.add(new Dining.Seat(chair.immutable(), approach.immutable()));
                }
            }
        }
        return Optional.of(new Cabinet(level, zone.id(), at.immutable(), menu, approaches, List.copyOf(seats)));
    }

    static List<BlockPos> cabinetBlocks(ServerLevel level, ZoneView zone) {
        if (zone.cells().size() > Dining.MAX_DINING_ZONE_CELLS) {
            return List.of();
        }
        return zone.cells().stream().flatMap(floor -> java.util.stream.Stream.of(floor, floor.above()))
            .distinct().filter(at -> DiningDrinks.hasSupply(level, at)).toList();
    }

    static Set<WorldPos> cabinetCells(ServerLevel level, List<BlockPos> blocks) {
        var cells = new HashSet<WorldPos>();
        for (BlockPos at : blocks) {
            cells.addAll(ContainerFacility.chestCells(level, WorldPos.of(level, at)));
        }
        return Set.copyOf(cells);
    }

    private void updateBodies(Map<Zone, Set<WorldPos>> next) {
        var changed = new HashMap<ServerLevel, Set<WorldPos>>();
        var zones = new HashSet<>(bodies.keySet());
        zones.addAll(next.keySet());
        for (Zone zone : zones) {
            var before = bodies.getOrDefault(zone, Set.of());
            var after = next.getOrDefault(zone, Set.of());
            if (!before.equals(after)) {
                var cells = changed.computeIfAbsent(zone.level(), ignored -> new HashSet<>());
                cells.addAll(before);
                cells.addAll(after);
            }
        }
        bodies = Map.copyOf(next);
        changed.forEach((level, cells) -> {
            Facilities.ownershipChanged(level);
            cells.forEach(at -> InventoryFeedback.store(level, at));
        });
    }

    void observe() {
        for (Cabinet cabinet : cabinets) {
            observe(cabinet, DiningDrinks.supply(cabinet.level, cabinet.at).orElse(null));
        }
    }

    void observe(Cabinet cabinet, Container inventory) {
        WorldPos at = WorldPos.of(cabinet.level, cabinet.at);
        if (inventory == null) {
            stock.remove(at);
            return;
        }
        List<ItemStack> slots = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            slots.add(inventory.getItem(slot).copy());
        }
        stock.put(at, List.copyOf(slots));
    }

    List<ItemStack> stock(Cabinet cabinet) {
        return stock.getOrDefault(WorldPos.of(cabinet.level, cabinet.at), List.of());
    }

    List<Grown> goals(ColonyView view) {
        var wanted = new ArrayList<Grown>();
        for (var cabinet : cabinets) {
            if (cabinet.level != view.level() || !current(cabinet)) { continue; }
            var inventory = stock(cabinet);
            for (int slot = 0; slot < inventory.size(); slot++) {
                if (!inventory.get(slot).isEmpty()) { continue; }
                var key = new Slot(WorldPos.of(cabinet.level, cabinet.at), cabinet.zone, cabinet.menu, slot);
                var backoff = refused.getOrDefault(key, Map.of());
                var accepted = Goods.members(cabinet.menu).stream().map(item -> item.getDefaultInstance())
                    .filter(DiningDrinks::accepts)
                    .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                    .filter(drink -> backoff.getOrDefault(drink, 0L) <= cabinet.level.getGameTime()).toList();
                if (accepted.isEmpty()) { continue; }
                var drink = refills.compute(key, (ignored, chosen) -> chosen != null && accepted.contains(chosen)
                    ? chosen : accepted.get(random.nextInt(accepted.size())));
                wanted.add(Grown.of(new StockCabinet(cabinet, key, drink)));
            }
        }
        return List.copyOf(wanted);
    }

    List<Cabinet> cabinets() { return cabinets; }

    boolean current(Cabinet cabinet) {
        return Fronts.of(colony).zonesIn(cabinet.level).stream().anyMatch(zone -> zone.id().equals(cabinet.zone)
                && zone.delegation().equals(LivingContent.DINING) && includes(zone, cabinet.at)
                && zone.settings().items(LivingContent.DINING_DRINK.key()).filter(cabinet.menu::equals).isPresent());
    }

    private static boolean includes(ZoneView zone, BlockPos at) {
        return zone.cells().contains(at) || zone.cells().contains(at.below());
    }

    void clear() {
        cabinets = List.of();
        refills.clear();
        refused.clear();
        stock.clear();
        updateBodies(Map.of());
    }

    private final class StockCabinet implements Node {
        private final Cabinet cabinet;
        private final Slot slot;
        private final ItemSpec drink;
        private final NodeSpec spec;

        StockCabinet(Cabinet cabinet, Slot slot, ItemSpec drink) {
            this.cabinet = cabinet; this.slot = slot; this.drink = drink;
            var id = UUID.nameUUIDFromBytes(("drink_cabinet/" + slot + "/" + drink.describe()).getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, LivingContent.ID, WorkSite.at(cabinet.level, cabinet.at),
                    Stances.of(cabinet.level, cabinet.approaches).orElseThrow(), Workload.Once.of(20))
                .needs(new Need(drink, 1)).doing(LivingContent.SETTING_TABLE, drink).focus(cabinet.at).done();
        }

        public NodeSpec spec() { return spec; }

        public Outcome commit(ServerLevel level, Worker who) {
            var back = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
            var inventory = DiningDrinks.supply(cabinet.level, cabinet.at).orElse(null);
            if (level != cabinet.level || who.body().level() != level || !current(cabinet)
                    || inventory == null || slot.slot >= inventory.getContainerSize() || !inventory.getItem(slot.slot).isEmpty()) {
                observe(cabinet, inventory);
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (var stack : back) {
                if (!stack.isEmpty() && Goods.matches(stack, drink) && DiningDrinks.accepts(stack)) {
                    if (!inventory.canPlaceItem(slot.slot, stack)) {
                        refused.computeIfAbsent(slot, ignored -> new ConcurrentHashMap<>())
                            .put(drink, level.getGameTime() + 200);
                        refills.remove(slot, drink);
                        continue;
                    }
                    inventory.setItem(slot.slot, stack.copyWithCount(1)); inventory.setChanged(); stack.shrink(1);
                    observe(cabinet, inventory);
                    refused.remove(slot);
                    refills.remove(slot, drink);
                    return new Outcome.Done(List.of(), List.of(), Optional.empty(), back.stream().filter(item -> !item.isEmpty()).toList());
                }
            }
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
        }
    }
}
