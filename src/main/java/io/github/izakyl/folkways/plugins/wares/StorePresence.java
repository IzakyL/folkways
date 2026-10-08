package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

// Offers the colony's work every container held as a store, again whenever one is taken up or let go.
final class StorePresence implements Holding.Watch {

    private final Colony colony;

    private final MinecraftServer server;

    private final Set<ResourceKey<Level>> offeredIn = new LinkedHashSet<>();

    StorePresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    @Override
    public void held(Holding holding) {
        offer();
    }

    @Override
    public void released(Holding holding, Release why) {
        offer();
    }

    private void offer() {
        Map<ResourceKey<Level>, List<WorldPos>> byDimension = new LinkedHashMap<>();
        for (Holding holding : colony.holdings(WaresContent.STORE)) {
            if (holding.what() instanceof Held.Block(WorldPos cell)) {
                cell.level(server).ifPresent(level ->
                    byDimension.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(cell));
            }
        }
        for (ResourceKey<Level> gone : offeredIn) {
            if (!byDimension.containsKey(gone)) {
                colony.stores(WaresContent.ID, gone, List.of());
            }
        }
        byDimension.forEach((dimension, stores) -> colony.stores(WaresContent.ID, dimension, stores));
        offeredIn.clear();
        offeredIn.addAll(byDimension.keySet());
    }
}
