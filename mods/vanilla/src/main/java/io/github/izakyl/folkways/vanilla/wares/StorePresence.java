package io.github.izakyl.folkways.vanilla.wares;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
final class StorePresence implements Claim.Watch {

    private final Colony colony;

    private final MinecraftServer server;

    private final Set<ResourceKey<Level>> offeredIn = new LinkedHashSet<>();

    StorePresence(Colony colony, MinecraftServer server) {
        this.colony = colony;
        this.server = server;
    }

    @Override
    public void held(Claim holding) {
        offer();
    }

    @Override
    public void released(Claim holding, ClaimRelease why) {
        offer();
    }

    private void offer() {
        Map<ResourceKey<Level>, List<WorldPos>> byDimension = new LinkedHashMap<>();
        for (Claim holding : colony.claims(WaresContent.STORE)) {
            holding.what().singleCell().ifPresent(cell -> cell.level(server).ifPresent(level ->
                byDimension.computeIfAbsent(level.dimension(), key -> new ArrayList<>()).add(cell)));
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
