package io.github.izakyl.folkways.plugins.fishing;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

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
