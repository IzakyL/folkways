package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.colony.Sweep;
import io.github.izakyl.folkways.base.persist.Reader;
import io.github.izakyl.folkways.base.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.front.api.Facing;
import io.github.izakyl.folkways.front.api.FrontView;
import io.github.izakyl.folkways.front.api.Fronts;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.front.api.Settings;
import io.github.izakyl.folkways.front.api.ZoneView;
import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

final class FarmPresence implements Facing {

    private static final String TAG_MADE = "made";

    // Plots with different harvest or clearing choices are worked as different fields.
    private record Plot(ResourceKey<Level> dimension, ResourceLocation crop, ResourceLocation harvest,
            Tillage.Clearing clearing) {
    }

    private final Colony colony;

    private final Sweep sweep;

    private final Map<Plot, Field> fields = new LinkedHashMap<>();

    private final PastDay made = new PastDay();

    // What the last sweep found on each plot, for the farms page.
    private record Sight(Crop crop, Field.Tally tally) {
    }

    private volatile Map<UUID, Sight> sights = Map.of();

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
        Map<UUID, Sight> seen = new LinkedHashMap<>();
        for (ColonyView view : colony.views(server)) {
            ServerLevel level = view.level();
            for (ZoneView plot : front.zonesIn(view.level())) {
                if (plot.delegation().equals(FarmingContent.PLOT)) {
                    scan(level, plot, stamp, seen).ifPresent(chosen::add);
                }
            }
        }
        sights = Map.copyOf(seen);
        retireAllBut(chosen);
        for (Field field : fields.values()) {
            field.settle(stamp);
        }
        keepMade();
    }

    public void closed(Closing why) {
        sights = Map.of();
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

    private Optional<Plot> scan(ServerLevel level, ZoneView zone, long stamp, Map<UUID, Sight> seen) {
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
            Item preferred = seeds.stream().filter(seed -> Fronts.of(colony)
                .stocked(level.getServer(), ItemSpec.of(seed)) > 0)
                .findFirst().orElse(seeds.getFirst());
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
        seen.put(zone.id(), new Sight(crop.get(), field.scan(level, zone, crop.get(), stamp)));
        return Optional.of(plot);
    }

    // A plot not swept yet, or sown with a crop that cannot grow there, is listed as left untended.
    @Override
    public Optional<Board> board(ResourceLocation page, ColonyView view) {
        if (!page.equals(FarmingContent.ID)) {
            return Optional.empty();
        }
        Map<UUID, Sight> known = sights;
        int plots = 0;
        int ripe = 0;
        List<Board.Row> rows = new ArrayList<>();
        for (ZoneView zone : Fronts.of(colony).zonesIn(view.level())) {
            if (!zone.delegation().equals(FarmingContent.PLOT) || zone.cells().isEmpty()) {
                continue;
            }
            plots++;
            ResourceLocation chosen = zone.settings().choice(FarmingContent.CROP);
            Sight sight = known.get(zone.id());
            List<Board.Act> acts = List.of(new Board.Act.Ping(zone.cells().stream()
                .min(Comparator.comparingLong(BlockPos::asLong)).orElseThrow()));
            if (sight == null) {
                rows.add(new Board.Row(new ItemStack(Items.WHEAT_SEEDS), nameOf(chosen),
                    Component.translatable("folkways.page.farm.idle"), acts));
                continue;
            }
            Field.Tally tally = sight.tally();
            ripe += tally.ripe();
            rows.add(new Board.Row(new ItemStack(sight.crop().seed()), nameOf(chosen),
                Component.translatable("folkways.page.farm.head", zone.cells().size(), tally.ripe(), tally.till(),
                    tally.sow()),
                acts));
        }
        return Optional.of(new Board(
            List.of(new Board.Figure("folkways.page.farm.plots", Component.literal(Integer.toString(plots))),
                new Board.Figure("folkways.page.farm.ripe", Component.literal(Integer.toString(ripe)))),
            rows,
            Optional.of(Component.translatable("folkways.page.farm.empty"))));
    }

    // A crop reads as the item of its own name where there is one (wheat, not wheat crops), else as its block; a
    // sapling grown four to a square reads as that sapling's variant.
    private static Component nameOf(ResourceLocation crop) {
        int stroke = crop.getPath().lastIndexOf('/');
        if (stroke > 0) {
            return Component.translatable("folkways.choice.variant." + crop.getPath().substring(stroke + 1),
                nameOf(crop.withPath(crop.getPath().substring(0, stroke))));
        }
        return BuiltInRegistries.ITEM.getOptional(crop)
            .filter(item -> item != Items.AIR)
            .map(Item::getDescription)
            .or(() -> BuiltInRegistries.BLOCK.getOptional(crop).map(Block::getName))
            .orElseGet(() -> Component.literal(crop.getPath()));
    }

}
