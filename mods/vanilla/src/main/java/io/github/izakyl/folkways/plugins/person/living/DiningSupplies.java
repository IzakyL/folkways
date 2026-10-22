package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.ZoneView;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Real drink cabinets inside a dining zone: deliveries stock them, ordinary hauling takes from them. */
final class DiningSupplies {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "dining_drinks");
    private final Colony colony;
    private final java.util.random.RandomGenerator random;
    private volatile List<Cabinet> cabinets = List.of();
    private final Set<ResourceKey<Level>> offered = new HashSet<>();
    private final Map<Slot, ItemSpec> refills = new java.util.concurrent.ConcurrentHashMap<>();
    private record Cabinet(ServerLevel level, UUID zone, BlockPos at, ItemSpec menu, List<BlockPos> approaches) { }
    private record Slot(WorldPos at, UUID zone, ItemSpec menu, int slot) { }

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
                if (seen.contains(position) || DiningDrinks.supply(level, at).isEmpty()) { continue; }
                var approaches = Direction.Plane.HORIZONTAL.stream().map(at::relative)
                    .filter(p -> level.isLoaded(p) && Footing.withFeetAt(level, p) && level.getFluidState(p).isEmpty()).toList();
                if (approaches.isEmpty()) { continue; }
                seen.add(position);
                found.add(new Cabinet(level, zone.id(), at.immutable(), menu.get(), approaches));
                byDimension.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(position);
            }
        }
        cabinets = List.copyOf(found);
        for (var dimension : offered) if (!byDimension.containsKey(dimension)) { colony.stores(OWNER, dimension, List.of()); }
        byDimension.forEach((dimension, stores) -> colony.stores(OWNER, dimension, stores));
        offered.clear(); offered.addAll(byDimension.keySet());
        refills.keySet().removeIf(slot -> cabinets.stream().noneMatch(cabinet -> slot.at.equals(WorldPos.of(cabinet.level, cabinet.at))
            && slot.zone.equals(cabinet.zone) && slot.menu.equals(cabinet.menu)));
    }

    List<Grown> goals(ColonyView view) {
        var wanted = new ArrayList<Grown>();
        for (var cabinet : cabinets) {
            if (cabinet.level != view.level() || !valid(cabinet)) { continue; }
            var inventory = DiningDrinks.supply(cabinet.level, cabinet.at).orElseThrow();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                if (!inventory.getItem(slot).isEmpty()) { continue; }
                final int index = slot;
                var accepted = Goods.members(cabinet.menu).stream().map(item -> item.getDefaultInstance())
                    .filter(DiningDrinks::accepts).filter(stack -> inventory.canPlaceItem(index, stack))
                    .map(stack -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(stack.getItem()))).toList();
                if (accepted.isEmpty()) { continue; }
                var key = new Slot(WorldPos.of(cabinet.level, cabinet.at), cabinet.zone, cabinet.menu, slot);
                var drink = refills.compute(key, (ignored, chosen) -> chosen != null && accepted.contains(chosen)
                    ? chosen : accepted.get(random.nextInt(accepted.size())));
                wanted.add(Grown.of(new StockCabinet(cabinet, key, drink)));
            }
        }
        return List.copyOf(wanted);
    }

    private boolean valid(Cabinet cabinet) {
        return DiningDrinks.supply(cabinet.level, cabinet.at).isPresent()
            && Fronts.of(colony).zonesIn(cabinet.level).stream().anyMatch(zone -> zone.id().equals(cabinet.zone)
                && zone.delegation().equals(LivingContent.DINING) && includes(zone, cabinet.at)
                && zone.settings().items(LivingContent.DINING_DRINK.key()).filter(cabinet.menu::equals).isPresent());
    }
    private static boolean includes(ZoneView zone, BlockPos at) { return zone.cells().contains(at) || zone.cells().contains(at.below()); }

    void clear() {
        cabinets = List.of(); refills.clear();
        for (var dimension : offered) { colony.stores(OWNER, dimension, List.of()); }
        offered.clear();
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
        public boolean acceptsInput(Stash from) {
            // Refilling a cabinet must add new stock rather than shuffle the existing
            // bottles between its own slots or between neighboring drink cabinets.
            return cabinets.stream().noneMatch(other -> WorldPos.of(other.level, other.at).equals(from.pos()));
        }
        public Outcome commit(ServerLevel level, Worker who) {
            var back = new ArrayList<>(who.supplied().stream().map(ItemStack::copy).toList());
            var inventory = DiningDrinks.supply(cabinet.level, cabinet.at).orElse(null);
            boolean nearby = Reach.stanceHolding(who.body(), spec.stances()).isPresent();
            if (level != cabinet.level || who.body().level() != level || !nearby || !valid(cabinet)
                    || inventory == null || slot.slot >= inventory.getContainerSize() || !inventory.getItem(slot.slot).isEmpty()) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
            }
            for (var stack : back) {
                if (!stack.isEmpty() && Goods.matches(stack, drink) && DiningDrinks.accepts(stack) && inventory.canPlaceItem(slot.slot, stack)) {
                    inventory.setItem(slot.slot, stack.copyWithCount(1)); inventory.setChanged(); stack.shrink(1);
                    refills.remove(slot, drink);
                    return new Outcome.Done(List.of(), List.of(), Optional.empty(), back.stream().filter(item -> !item.isEmpty()).toList());
                }
            }
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), back);
        }
    }
}
