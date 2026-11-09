package io.github.izakyl.folkways.core.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

public record ColonyMetrics(
    int residents,
    int idle,
    int members,
    int zones,
    List<Held> stock,
    boolean complete
) {
    public static final ColonyMetrics EMPTY = new ColonyMetrics(0, 0, 0, 0, List.of(), true);

    public record Held(ItemSpec spec, int count) {
        public ResourceLocation item() {
            return spec.item().orElseThrow();
        }

        public ItemStack stack() {
            return Goods.display(spec);
        }
    }

    public ColonyMetrics {
        stock = List.copyOf(stock);
    }

    public static ColonyMetrics of(MinecraftServer server, Colony colony) {
        List<Body> residents = Residents.of(colony, server);
        int idle = 0;
        for (Body resident : residents) {
            if (resident.doing().isEmpty()) {
                idle++;
            }
        }
        int members = (int) colony.claims().stream()
            .filter(holding -> holding.what().singleCell().isPresent()).count();
        List<Held> stock = new ArrayList<>();
        boolean complete = colony.inventory().snapshot().coverage().complete();
        if (!complete) {
            colony.inventory().ensureObserved();
        }
        Map<ItemSpec, Integer> tally = complete ? ColonyStocks.tally(colony, server) : Map.of();
        tally.forEach((item, count) -> stock.add(new Held(item, count)));
        stock.sort(Comparator.comparingInt(Held::count).reversed());
        return new ColonyMetrics(residents.size(), idle, members,
            ColonyFront.of(colony).zones().size(), stock, complete);
    }

    public int kinds() {
        return stock.size();
    }

    public int goods() {
        int total = 0;
        for (Held held : stock) {
            total += held.count();
        }
        return total;
    }
}
