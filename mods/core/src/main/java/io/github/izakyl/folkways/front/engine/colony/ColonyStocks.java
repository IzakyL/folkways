package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.ColonyInventory;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Stream;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

public final class ColonyStocks {

    private static final Map<ColonyInventory, Counted> TOTALS = new WeakHashMap<>();

    private record Stock(ItemStack stack, long count) {
    }

    private static final class Counted {
        private final InventorySnapshot.Version version;
        private final Map<ItemSpec, Long> goods;
        private final List<Stock> stacks;
        private final Map<ItemSpec, Long> matches = new HashMap<>();
        private int matchingTick = Integer.MIN_VALUE;

        private Counted(InventorySnapshot.Version version, Map<ItemSpec, Long> goods) {
            this.version = version;
            this.goods = goods;
            stacks = goods.entrySet().stream()
                .map(entry -> new Stock(Goods.display(entry.getKey()), entry.getValue())).toList();
        }

        private long available(ItemSpec spec, int tick) {
            // Tags can change on a reload without changing inventory contents.
            if (matchingTick != tick) {
                matchingTick = tick;
                matches.clear();
            }
            return matches.computeIfAbsent(spec, query -> stacks.stream()
                .filter(stock -> Goods.matches(stock.stack(), query)).mapToLong(Stock::count).sum());
        }
    }

    private ColonyStocks() {
    }

    public static Map<ItemSpec, Integer> tally(Colony colony, MinecraftServer server) {
        Map<ItemSpec, Integer> held = new LinkedHashMap<>();
        counted(colony).goods.forEach((spec, count) -> held.put(spec, count.intValue()));
        return held;
    }

    public static synchronized long available(Colony colony, MinecraftServer server, ItemSpec spec) {
        return counted(colony).available(spec, server.getTickCount());
    }

    private static synchronized Counted counted(Colony colony) {
        ColonyInventory inventory = colony.inventory();
        InventorySnapshot snapshot = inventory.snapshot();
        if (!snapshot.coverage().complete()) {
            inventory.ensureObserved();
        }
        var version = snapshot.version();
        Counted previous = TOTALS.get(inventory);
        if (previous != null && previous.version.epoch().equals(version.epoch())
            && previous.version.contents() == version.contents()
            && previous.version.topology() == version.topology()) {
            return previous;
        }
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        slots(snapshot).map(SlotSnapshot::stackCopy).filter(stack -> !stack.isEmpty())
            .forEach(stack -> held.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum));
        Map<ItemSpec, Long> goods = Map.copyOf(held);
        Counted counted = new Counted(version, goods);
        TOTALS.put(inventory, counted);
        return counted;
    }

    private static Stream<SlotSnapshot> slots(InventorySnapshot snapshot) {
        return Stream.concat(snapshot.stores().values().stream().flatMap(store -> store.slots().stream()),
            snapshot.residents().values().stream().flatMap(resident -> resident.pack().stream()));
    }
}
