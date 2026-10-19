package io.github.izakyl.folkways.plugins.supply;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

final class SupplyPresence implements Facing, Holding.Watch {
    private final Colony colony;
    private final Map<WorldPos, SupplyWorkshop> workshops = new LinkedHashMap<>();
    private final Set<ResourceKey<Level>> published = new LinkedHashSet<>();
    private boolean dirty = true;
    private long next;

    SupplyPresence(Colony colony, MinecraftServer server) { this.colony = colony; }
    public void held(Holding holding) { dirty = true; }
    public void released(Holding holding, Release why) { dirty = true; }

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

    public boolean shows(ResourceLocation page) {
        return !page.equals(SupplyContent.ID) || !colony.holdings(SupplyContent.SOURCE).isEmpty();
    }

    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(SupplyContent.ID)) return Optional.empty();
        List<Board.Row> rows = new ArrayList<>();
        for (BlockPos cell : view.blocks(SupplyContent.SOURCE)) {
            if (view.level().isLoaded(cell) && view.level().getBlockEntity(cell) instanceof SupplyBox source) {
                rows.add(new Board.Row(new ItemStack(SupplyContent.ITEM.get()),
                    Component.translatable("block.folkways.creative_supply_box"), state(source), List.of(new Board.Act.Ping(cell))));
            }
        }
        return Optional.of(new Board(List.of(), rows, Optional.of(Component.translatable("folkways.supply.empty"))));
    }

    public List<Line> blockLines(BlockPos at, ColonyView view) {
        return view.level().getBlockEntity(at) instanceof SupplyBox box ? List.of(Line.text(state(box))) : List.of();
    }

    private static Component state(SupplyBox source) {
        return Component.translatable("folkways.supply." + (source.allItems() ? "all" : "only"));
    }
}
