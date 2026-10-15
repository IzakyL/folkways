package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

final class FarmPresence implements Facing {

    private static final String TAG_MADE = "made";

    private record Plot(ResourceKey<Level> dimension, ResourceLocation crop, ResourceLocation harvest) {
    }

    private final Colony colony;

    private final Sweep sweep;

    private final Map<Plot, Field> fields = new LinkedHashMap<>();

    private final PastDay made = new PastDay();

    private long sweeps;

    FarmPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, FarmingContent.ID, 100);
        Reader.of(colony.kept(FarmingContent.ID)).child(TAG_MADE).ifPresent(made::load);
    }

    @Override
    public List<Line> zoneLines(ZoneView zone, ColonyView view) {
        return zone.delegation().equals(FarmingContent.PLOT) ? made.lines(zone, view) : List.of();
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        long stamp = ++sweeps;
        FrontView front = Fronts.of(colony);
        Set<Plot> chosen = new LinkedHashSet<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            for (ZoneView plot : front.zonesIn(view.level())) {
                if (plot.delegation().equals(FarmingContent.PLOT)) {
                    scan(level, plot, stamp).ifPresent(chosen::add);
                }
            }
        }
        retireAllBut(chosen);
        for (Field field : fields.values()) {
            field.settle(stamp);
        }
        keepMade();
    }

    public void closed(Closing why) {
        retireAllBut(Set.of());
        keepMade();
    }

    private void keepMade() {
        if (made.changed()) {
            colony.keep(FarmingContent.ID, Writer.of().blob(TAG_MADE, made.save()).tag());
        }
    }

    private void retireAllBut(Set<Plot> chosen) {
        fields.entrySet().removeIf(entry -> {
            if (chosen.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    public List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        for (Field field : fields.values()) {
            field.chores().forEach(chores -> chores.goals(view.level(), goals));
        }
        return goals;
    }

    private Optional<Plot> scan(ServerLevel level, ZoneView zone, long stamp) {
        Optional<BlockPos> anywhere = zone.cells().stream().filter(level::isLoaded).findFirst();
        if (anywhere.isEmpty()) {
            return Optional.empty();
        }
        ResourceLocation chosen = zone.settings().choice(FarmingContent.CROP);
        Optional<Crop> crop = Crop.of(level, anywhere.get(), chosen);
        if (crop.isEmpty()) {
            return Optional.empty();
        }
        if (crop.get().behavior().isPresent()) {
            List<net.minecraft.world.item.Item> seeds = crop.get().behavior().get().seeds();
            net.minecraft.world.item.Item preferred = seeds.stream().filter(seed -> Fronts.of(colony)
                .stocked(level.getServer(), io.github.izakyl.folkways.core.api.terms.Goods.specOf(seed.getDefaultInstance())) > 0)
                .findFirst().orElse(seeds.getFirst());
            crop = Optional.of(crop.get().using(preferred));
        }
        ResourceLocation harvest = zone.settings().choice(FarmingContent.HARVEST_MODE);
        if (harvest == null) harvest = HarvestMethods.PICK;
        crop = Optional.of(crop.get().harvesting(harvest));
        Plot plot = new Plot(level.dimension(), chosen, harvest);
        Field field = fields.get(plot);
        if (field == null) {
            field = new Field(crop.get(), WorldPos.of(level, anywhere.get()), sweep::nudge, made);
            fields.put(plot, field);
        }
        field.scan(level, zone, crop.get(), stamp);
        return Optional.of(plot);
    }

}
