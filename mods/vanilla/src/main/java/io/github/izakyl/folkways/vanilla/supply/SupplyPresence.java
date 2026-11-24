package io.github.izakyl.folkways.vanilla.supply;

import io.github.izakyl.folkways.core.card.api.notice.Line;
import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

final class SupplyPresence implements HoldingKind, Claim.Watch {
    private final Colony colony;
    private final Map<WorldPos, SupplyWorkshop> workshops = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private boolean dirty = true;
    private long next;

    SupplyPresence(Colony colony, MinecraftServer server) { this.colony = colony; }
    public void held(Claim holding) { dirty = true; }
    public void released(Claim holding, ClaimRelease why) { dirty = true; }

    void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (!dirty && now < next) return;
        dirty = false;
        next = now + 20;
        Map<WorldPos, SupplyWorkshop> current = new LinkedHashMap<>();
        Set<ResourceKey<Level>> dimensions = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            var level = view.level();
            dimensions.add(level.dimension());
            List<Workshop> available = new ArrayList<>();
            for (BlockPos cell : view.blocks(SupplyContent.SOURCE)) {
                if (!level.isLoaded(cell) || !(level.getBlockEntity(cell) instanceof SupplyBox source)) continue;
                WorldPos at = WorldPos.of(level, cell);
                Stances.of(level, Reach.workableCells(level, cell)).ifPresent(stances -> {
                    SupplyWorkshop was = workshops.get(at);
                    SupplyWorkshop shop = was != null && was.source() == source && was.revision() == source.revision()
                        && was.stances().equals(stances) ? was
                        : new SupplyWorkshop(UUID.randomUUID(), colony, at, stances, source, source.revision());
                    current.put(at, shop);
                    available.add(shop);
                });
            }
            colony.publish(SupplyContent.ID, level.dimension(), available);
        }
        for (ResourceKey<Level> dimension : published) {
            if (!dimensions.contains(dimension)) colony.publish(SupplyContent.ID, dimension, List.of());
        }
        published.clear();
        published.addAll(dimensions);
        workshops.clear();
        workshops.putAll(current);
    }

    void closed(Closing why) {
        for (ResourceKey<Level> dimension : published) colony.publish(SupplyContent.ID, dimension, List.of());
        workshops.clear();
        published.clear();
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        BlockPos at = held.site().block(view.level());
        return Containers.blockEntity(view.level(), at).orElse(null) instanceof SupplyBox box
            ? List.of(Line.text(state(box))) : List.of();
    }

    private static Component state(SupplyBox source) {
        return Component.translatable("folkways.supply." + (source.allItems() ? "all" : "only"));
    }
}
