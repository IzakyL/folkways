package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;

public record ColonyMetrics(
    int residents,
    int idle,
    int members,
    int zones,
    List<Held> stock
) {
    public static final ColonyMetrics EMPTY = new ColonyMetrics(0, 0, 0, 0, List.of());

    public record Held(ResourceLocation item, int count) {
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
        int members = (int) colony.holdings().stream()
            .filter(holding -> holding.what() instanceof io.github.izakyl.folkways.core.api.colony.Held.Block).count();
        List<Held> stock = new ArrayList<>();
        Map<Item, Integer> tally = ColonyStocks.tally(colony, server);
        tally.forEach((item, count) -> stock.add(new Held(BuiltInRegistries.ITEM.getKey(item), count)));
        stock.sort(Comparator.comparingInt(Held::count).reversed());
        return new ColonyMetrics(residents.size(), idle, members,
            ColonyFront.of(colony).zones().size(), stock);
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
