package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** Reconciled, floor-level infrastructure work using the normal construction labor and material costs. */
public final class ConstructionNeeds {
    private record Request(BlockState state, BuildSite site) { }

    private final Map<WorldPos, Request> requests = new LinkedHashMap<>();
    private final BuildSite.Asks asks;

    public ConstructionNeeds(Colony colony, ResourceLocation owner) {
        asks = new BuildSite.Asks() {
            @Override
            public void submit(ResourceKey<Level> dimension, Grown work, BiConsumer<UUID, Ending> ended) {
                colony.submit(owner, dimension, work, ended);
            }

            @Override
            public void withdraw(UUID node) {
                colony.withdraw(owner, node);
            }
        };
    }

    /** Replaces this owner's desired cells in this level; absent cells have their work withdrawn. */
    public void refresh(ServerLevel level, Map<WorldPos, BlockState> desired) {
        requests.entrySet().removeIf(entry -> {
            if (!entry.getKey().in(level)) {
                return false;
            }
            if (entry.getValue().state().equals(desired.get(entry.getKey()))) {
                return false;
            }
            entry.getValue().site().close();
            return true;
        });
        desired.forEach((at, state) -> {
            if (!at.in(level)) {
                return;
            }
            Request request = requests.get(at);
            if (request == null) {
                var cell = at.block(level);
                // Automatic infrastructure must never excavate an existing building or drain a fluid.
                if (!level.getBlockState(cell).canBeReplaced() || !level.getFluidState(cell).isEmpty()) {
                    return;
                }
                BuildTarget target = new BuildTarget(at.realm(), Map.of(cell, state), Set.of(), cell.subtract(at.cell()));
                BuildSite site = new BuildSite(UUID.randomUUID(), asks, target, new TemporaryScaffolds(() -> { }));
                site.withoutScaffolds();
                request = new Request(state, site);
                requests.put(at, request);
                site.start(level, Set.of());
            }
            request.site().tick(level);
        });
    }

    public void close() {
        requests.values().forEach(request -> request.site().close());
        requests.clear();
    }
}
