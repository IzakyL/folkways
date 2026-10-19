package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

final class FishingPresence implements Facing {

    private static final String TAG_MADE = "made";

    private final Colony colony;

    private final Sweep sweep;

    private final PastDay made = new PastDay();

    private volatile Map<UUID, Spot> fisheries = Map.of();

    FishingPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, FishingContent.ID, 100);
        Reader.of(colony.kept(FishingContent.ID)).child(TAG_MADE).ifPresent(made::load);
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(FishingContent.FISHERY) ? made.lines(zone, view) : List.of();
    }

    // A fishery with no water a fisher can cast into from it is listed as dry, pinging the ground marked out.
    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(FishingContent.ID)) {
            return Optional.empty();
        }
        Map<UUID, Spot> known = fisheries;
        int count = 0;
        long caught = 0;
        List<Board.Row> rows = new ArrayList<>();
        for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(FishingContent.FISHERY) || zone.cells().isEmpty()) {
                continue;
            }
            count++;
            Spot spot = known.get(zone.id());
            Component title = Component.translatable("folkways.delegation.folkways.fish");
            if (spot == null) {
                BlockPos marked = zone.cells().stream().min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow();
                rows.add(new Board.Row(new ItemStack(Items.FISHING_ROD), title,
                    Component.translatable("folkways.page.fish.dry"), List.of(new Board.Act.Ping(marked))));
                continue;
            }
            long here = made.madeOn(zone, view).values().stream().mapToLong(Long::longValue).sum();
            caught += here;
            rows.add(new Board.Row(new ItemStack(Items.FISHING_ROD), title,
                Component.translatable("folkways.page.fish.head", here),
                List.of(new Board.Act.Ping(spot.water().block(view.level()))))
                .told(made.told(zone, view)));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.fish.count", Component.literal(Integer.toString(count))),
                new Board.Figure("folkways.page.fish.caught", Component.literal(Long.toString(caught)))),
            rows,
            Optional.of(Component.translatable("folkways.page.fish.empty"))));
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        fisheries = Map.copyOf(Fisheries.waters(Fronts.of(colony), colony.views(server)));
        keepMade();
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> casts = new ArrayList<>();
        fisheries.forEach((zone, spot) -> {
            if (spot.water().in(view.level())) {
                casts.add(Grown.of(new CastNode(idOf(zone, spot), spot, sweep::nudge, made)));
            }
        });
        return casts;
    }

    private static UUID idOf(UUID zone, Spot spot) {
        return UUID.nameUUIDFromBytes(("folkways:fish/" + zone + "/" + spot.water())
            .getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        fisheries = Map.of();
        keepMade();
    }

    private void keepMade() {
        if (made.changed()) {
            colony.keep(FishingContent.ID, Writer.of().blob(TAG_MADE, made.save()).tag());
        }
    }
}
