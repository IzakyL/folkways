package io.github.izakyl.folkways.vanilla.fishing;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.PastDay;
import io.github.izakyl.folkways.core.card.api.notice.Line;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

final class FishingPresence implements HoldingKind {

    private static final String TAG_MADE = "made";

    private final Colony colony;

    private final Sweep sweep;

    private final PastDay made = new PastDay();

    private volatile Map<UUID, Spot> fisheries = Map.of();

    FishingPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, FishingContent.ID, 100);
        Reader kept = Reader.of(colony.kept(FishingContent.ID));
        if (!kept.keys().isEmpty()) {
            made.load(kept.child(TAG_MADE).orElseThrow());
        }
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        return held.zone().filter(zone -> zone.delegation().equals(FishingContent.FISHERY))
            .map(zone -> made.lines(zone, view)).orElseGet(List::of);
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
        return UUID.nameUUIDFromBytes(("folkways_vanilla:fish/" + zone + "/" + spot.water())
            .getBytes(StandardCharsets.UTF_8));
    }

    public void closed(Closing why) {
        fisheries = Map.of();
        keepMade();
    }

    private void keepMade() {
        if (made.consumeChanged()) {
            colony.keep(FishingContent.ID, Writer.of().blob(TAG_MADE, made.save()).tag());
        }
    }
}
