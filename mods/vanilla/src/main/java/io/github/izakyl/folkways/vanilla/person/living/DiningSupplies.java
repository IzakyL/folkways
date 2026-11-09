package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
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
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Footing;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Real drink cabinets inside dining zones, stocked through ordinary colony deliveries. */
final class DiningSupplies {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "dining_drinks");
    private final Colony colony;
    private final java.util.random.RandomGenerator random;
    private volatile List<Cabinet> cabinets = List.of();
    private final Set<ResourceKey<Level>> offered = new HashSet<>();
    private final Map<Slot, ItemSpec> refills = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Slot, Map<ItemSpec, Refused>> refused = new HashMap<>();
    record Cabinet(ServerLevel level, UUID zone, BlockPos at, ItemSpec menu, List<BlockPos> approaches,
                   List<Dining.Seat> seats) { }
    private record Slot(WorldPos at, UUID zone, ItemSpec menu, int slot) { }
    private record Refused(InventorySnapshot.Version version, long incarnation, long until) { }

    DiningSupplies(Colony colony, java.util.random.RandomGenerator random) { this.colony = colony; this.random = random; }

    void refresh(MinecraftServer server) {
        var found = new ArrayList<Cabinet>();
        var seen = new HashSet<WorldPos>();
        var byDimension = new HashMap<ResourceKey<Level>, List<WorldPos>>();
        for (var view : colony.views(server)) for (var zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(LivingContent.DINING) || zone.cells().size() > 4096) { continue; }
            var menu = zone.settings().items(LivingContent.DINING_DRINK.key());
            if (menu.isEmpty()) { continue; }
            var level = view.level();
            for (var floor : zone.cells()) for (var at : List.of(floor, floor.above())) {
                var position = WorldPos.of(level, at);
                if (seen.contains(position)) { continue; }
                var inventory = DiningDrinks.supply(level, at).orElse(null);
                if (inventory == null) { continue; }
                var approaches = Direction.Plane.HORIZONTAL.stream().map(at::relative)
                    .filter(p -> level.isLoaded(p) && Footing.withFeetAt(level, p) && level.getFluidState(p).isEmpty()).toList();
                if (approaches.isEmpty()) { continue; }
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
                seen.add(position);
                found.add(new Cabinet(level, zone.id(), at.immutable(), menu.get(), approaches, List.copyOf(seats)));
                byDimension.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(position);
            }
        }
        for (Cabinet previous : cabinets) {
            WorldPos position = WorldPos.of(previous.level, previous.at);
            boolean unavailable = !previous.level.isLoaded(previous.at) || colony.inventory().snapshot().store(position)
                .filter(store -> store.state() == InventorySnapshot.State.UNLOADED).isPresent();
            if (unavailable && current(previous) && seen.add(position)) {
                found.add(previous);
                byDimension.computeIfAbsent(previous.level.dimension(), key -> new ArrayList<>()).add(position);
            }
        }
        cabinets = List.copyOf(found);
        for (var dimension : offered) if (!byDimension.containsKey(dimension)) {
            colony.stores(OWNER, dimension, List.of());
            colony.rule(OWNER, dimension, List.of());
        }
        byDimension.forEach((dimension, stores) -> {
            colony.stores(OWNER, dimension, stores);
            // Ordinary work may take drinks, but StockCabinet alone replenishes these slots.
            // Returned empty bottles must not claim the last slot before its refill arrives.
            colony.rule(OWNER, dimension, stores.stream()
                .<StoreRule>map(at -> new StoreRule.Access(at, true, false)).toList());
        });
        offered.clear(); offered.addAll(byDimension.keySet());
        refills.keySet().removeIf(slot -> cabinets.stream().noneMatch(cabinet -> slot.at.equals(WorldPos.of(cabinet.level, cabinet.at))
            && slot.zone.equals(cabinet.zone) && slot.menu.equals(cabinet.menu)));
        refused.keySet().removeIf(slot -> cabinets.stream().noneMatch(cabinet ->
            slot.at.equals(WorldPos.of(cabinet.level, cabinet.at)) && slot.zone.equals(cabinet.zone)
                && slot.menu.equals(cabinet.menu)));
    }

    List<Grown> goals(ColonyView view) {
        var wanted = new ArrayList<Grown>();
        var snapshot = colony.inventory().snapshot();
        for (var cabinet : cabinets) {
            if (cabinet.level != view.level() || !current(cabinet)) { continue; }
            var inventory = snapshot.store(WorldPos.of(cabinet.level, cabinet.at)).orElse(null);
            if (inventory == null || inventory.state() != InventorySnapshot.State.OBSERVED) {
                colony.inventory().ensureObserved(Stash.at(
                    WorldPos.of(cabinet.level, cabinet.at)));
                continue;
            }
            for (var observed : inventory.slots()) {
                int slot = observed.physicalSlot();
                if (!observed.stackCopy().isEmpty()) { continue; }
                var key = new Slot(WorldPos.of(cabinet.level, cabinet.at), cabinet.zone, cabinet.menu, slot);
                var accepted = Goods.members(cabinet.menu).stream().map(item -> item.getDefaultInstance())
                    .filter(DiningDrinks::accepts).filter(stack -> !inventory.refused().contains(stack.getItem()))
                    .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem())))
                    .filter(drink -> !refused(key, drink, inventory, cabinet.level.getGameTime())).toList();
                if (accepted.isEmpty()) { continue; }
                var drink = refills.compute(key, (ignored, chosen) -> chosen != null && accepted.contains(chosen)
                    ? chosen : accepted.get(random.nextInt(accepted.size())));
                wanted.add(Grown.of(new StockCabinet(cabinet, key, drink, inventory.version())));
            }
        }
        return List.copyOf(wanted);
    }

    private boolean refused(Slot slot, ItemSpec drink, InventorySnapshot.Store store, long now) {
        var choices = refused.get(slot);
        if (choices == null) { return false; }
        Refused prior = choices.get(drink);
        if (prior == null) { return false; }
        var version = store.version();
        var old = prior.version;
        if (prior.until <= now || prior.incarnation != store.incarnation()
                || !old.epoch().equals(version.epoch()) || old.contents() != version.contents()
                || old.policy() != version.policy() || old.topology() != version.topology()) {
            choices.remove(drink);
            if (choices.isEmpty()) { refused.remove(slot); }
            return false;
        }
        return true;
    }

    List<Cabinet> cabinets() { return cabinets; }

    boolean valid(Cabinet cabinet) {
        return DiningDrinks.supply(cabinet.level, cabinet.at).isPresent() && current(cabinet);
    }

    boolean current(Cabinet cabinet) {
        return Fronts.of(colony).zonesIn(cabinet.level).stream().anyMatch(zone -> zone.id().equals(cabinet.zone)
                && zone.delegation().equals(LivingContent.DINING) && includes(zone, cabinet.at)
                && zone.settings().items(LivingContent.DINING_DRINK.key()).filter(cabinet.menu::equals).isPresent());
    }
    private static boolean includes(ZoneView zone, BlockPos at) { return zone.cells().contains(at) || zone.cells().contains(at.below()); }

    void clear() {
        cabinets = List.of(); refills.clear(); refused.clear();
        for (var dimension : offered) {
            colony.stores(OWNER, dimension, List.of());
            colony.rule(OWNER, dimension, List.of());
        }
        offered.clear();
    }

    private final class StockCabinet implements Node {
        private final Cabinet cabinet;
        private final Slot slot;
        private final ItemSpec drink;
        private final NodeSpec spec;
        private final InventorySnapshot.Version observed;
        StockCabinet(Cabinet cabinet, Slot slot, ItemSpec drink, InventorySnapshot.Version observed) {
            this.cabinet = cabinet; this.slot = slot; this.drink = drink; this.observed = observed;
            var id = UUID.nameUUIDFromBytes(("drink_cabinet/" + slot + "/" + drink.describe()).getBytes(StandardCharsets.UTF_8));
            spec = NodeSpec.of(id, LivingContent.ID, WorkSite.at(cabinet.level, cabinet.at),
                    Stances.of(cabinet.level, cabinet.approaches).orElseThrow(), Workload.Once.of(20))
                .needs(new Need(drink, 1)).doing(LivingContent.SETTING_TABLE, drink).focus(cabinet.at).done();
        }
        public NodeSpec spec() { return spec; }
        public boolean acceptsInput(Stash from) {
            // Refilling a cabinet must add new stock rather than shuffle the existing
            // bottles between its own slots or between neighboring drink cabinets.
            return cabinets.stream().noneMatch(other -> WorldPos.of(other.level, other.at).equals(from.pos()));
        }
        public Outcome commit(ServerLevel level, Worker who) {
            var back = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
            var inventory = DiningDrinks.supply(cabinet.level, cabinet.at).orElse(null);
            boolean nearby = WorkStances.stanceHolding(who.body(), spec.stances()).isPresent();
            if (level != cabinet.level || who.body().level() != level || !nearby || !valid(cabinet)
                    || inventory == null || slot.slot >= inventory.getContainerSize() || !inventory.getItem(slot.slot).isEmpty()) {
                colony.inventoryFeedback().stale(Stash.at(slot.at), observed);
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (var stack : back) {
                if (!stack.isEmpty() && Goods.matches(stack, drink) && DiningDrinks.accepts(stack)) {
                    if (!inventory.canPlaceItem(slot.slot, stack)) {
                        var current = colony.inventory().snapshot().store(slot.at).orElse(null);
                        if (current != null) {
                            refused.computeIfAbsent(slot, ignored -> new HashMap<>()).put(drink,
                                new Refused(current.version(), current.incarnation(), level.getGameTime() + 200));
                        }
                        refills.remove(slot, drink);
                        colony.inventoryFeedback().stale(Stash.at(slot.at),
                            current == null ? observed : current.version());
                        continue;
                    }
                    inventory.setItem(slot.slot, stack.copyWithCount(1)); inventory.setChanged(); stack.shrink(1);
                    colony.inventoryFeedback().changed(Stash.at(slot.at));
                    refills.remove(slot, drink);
                    return new Outcome.Done(List.of(), List.of(), Optional.empty(), back.stream().filter(item -> !item.isEmpty()).toList());
                }
            }
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
        }
    }
}
