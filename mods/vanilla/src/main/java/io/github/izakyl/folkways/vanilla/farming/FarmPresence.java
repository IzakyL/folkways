package io.github.izakyl.folkways.vanilla.farming;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.colony.api.Sweep;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.api.HoldingKind;
import io.github.izakyl.folkways.core.front.api.FrontView;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.api.PastDay;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.front.api.ZoneView;
import io.github.izakyl.folkways.core.card.api.notice.Line;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

final class FarmPresence implements HoldingKind {

    private static final String TAG_MADE = "made";

    // Plots with different harvest or clearing choices are worked as different fields.
    private record Plot(ResourceKey<Level> dimension, ResourceLocation crop, ResourceLocation harvest,
            Tillage.Clearing clearing) {
    }

    private final Colony colony;

    private final Sweep sweep;

    private final Map<Plot, Field> fields = new LinkedHashMap<>();

    private final PastDay made = new PastDay();

    private long sweeps;

    FarmPresence(Colony colony) {
        this.colony = colony;
        this.sweep = new Sweep(colony, FarmingContent.ID, 100);
        Reader kept = Reader.of(colony.kept(FarmingContent.ID));
        if (!kept.keys().isEmpty()) {
            made.load(kept.child(TAG_MADE).orElseThrow());
        }
    }

    @Override
    public List<Line> lines(Held held, ColonyView view) {
        return held.zone().filter(zone -> zone.delegation().equals(FarmingContent.PLOT))
            .map(zone -> made.lines(zone, view)).orElseGet(List::of);
    }

    public void tick(MinecraftServer server) {
        sweep.tick(server, this::refresh, this::goals);
    }

    void refresh(MinecraftServer server) {
        if (!colony.inventory().snapshot().coverage().complete()) {
            colony.inventory().ensureObserved();
            return;
        }
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
        if (made.consumeChanged()) {
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

    List<CropSeeds.Sowing> sowingPlans(ServerLevel level) {
        return fields.values().stream().flatMap(field -> field.sowingPlans(level).stream()).toList();
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
            List<Item> seeds = crop.get().behavior().get().seeds();
            Item preferred =
                seeds.stream()
                .filter(seed -> Fronts.of(colony).stocked(level.getServer(), ItemSpec.of(seed)) > 0)
                .findFirst()
                .orElse(seeds.getFirst());
            crop = Optional.of(crop.get().using(preferred));
        }
        Settings settings = zone.settings();
        ResourceLocation harvest = settings.choice(FarmingContent.HARVEST_MODE);
        if (harvest == null) harvest = HarvestMethods.PICK;
        crop = Optional.of(crop.get().harvesting(harvest));
        Tillage.Clearing clearing = new Tillage.Clearing(settings.flag(FarmingContent.CLEAR_GRASS.key()),
            settings.flag(FarmingContent.CLEAR_FLOWERS.key()));
        Plot plot = new Plot(level.dimension(), chosen, harvest, clearing);
        Field field = fields.get(plot);
        if (field == null) {
            field = new Field(crop.get(), clearing, WorldPos.of(level, anywhere.get()), sweep::nudge, made);
            fields.put(plot, field);
        }
        field.scan(level, zone, crop.get(), stamp);
        return Optional.of(plot);
    }
}
