package io.github.izakyl.folkways.plugins.pasture;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.front.api.FrontView;
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
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.CampfireBlock;

/** Fixed hive work shares the herding trade, but needs no animal enclosure or head count. */
final class Apiaries {
    private record Harvest(boolean bottles, ApiaryHarvestNode node) { }
    private record Tally(int hives, int ripe, int unsafe) { }

    private final ApiaryCampfires campfires;
    private final PastDay made;
    private final Runnable changed;
    private final Map<WorldPos, Harvest> harvests = new LinkedHashMap<>();
    private final Map<UUID, ResourceKey<Level>> dimensions = new LinkedHashMap<>();
    private final Map<UUID, Tally> tallies = new LinkedHashMap<>();

    Apiaries(Colony colony, PastDay made, Runnable changed) {
        this.campfires = new ApiaryCampfires(colony);
        this.made = made;
        this.changed = changed;
    }

    void refresh(ColonyView view, FrontView front) {
        Set<WorldPos> standing = new LinkedHashSet<>();
        Set<UUID> zones = new LinkedHashSet<>();
        for (ZoneView zone : front.zonesIn(view.level())) {
            if (!zone.delegation().equals(PastureContent.APIARY)) continue;
            zones.add(zone.id());
            dimensions.put(zone.id(), view.dimension());
            boolean bottles = zone.settings().choice(PastureContent.PRODUCT.key())
                .equals(ResourceLocation.withDefaultNamespace("honey_bottle"));
            int hives = 0;
            int ripe = 0;
            int unsafe = 0;
            for (BlockPos cell : zone.cells()) {
                WorldPos at = zone.at(cell);
                if (!at.in(view.level())) continue;
                BlockPos pos = at.block(view.level());
                if (!view.level().isLoaded(pos)) continue;
                var state = view.level().getBlockState(pos);
                if (!(state.getBlock() instanceof BeehiveBlock)) continue;
                hives++;
                if (!CampfireBlock.isSmokeyPos(view.level(), pos)) unsafe++;
                if (state.getValue(BeehiveBlock.HONEY_LEVEL) == 5) ripe++;
                if (!standing.add(at)) continue;
                var footings = Stances.of(view.level(), Reach.workableCells(view.level(), pos));
                Harvest previous = harvests.get(at);
                if (previous != null && (previous.bottles() != bottles
                    || !previous.node().claimed() && !footings.equals(Optional.of(previous.node().spec().stances())))) {
                    previous.node().retire();
                    harvests.remove(at);
                    previous = null;
                }
                if (previous == null) {
                    footings.ifPresent(stances ->
                        harvests.put(at, new Harvest(bottles,
                            new ApiaryHarvestNode(at, stances, bottles, made, changed))));
                }
            }
            tallies.put(zone.id(), new Tally(hives, ripe, unsafe));
        }
        campfires.refresh(view, List.copyOf(standing));
        harvests.entrySet().removeIf(entry -> {
            if (!entry.getKey().in(view.level()) || standing.contains(entry.getKey())) return false;
            entry.getValue().node().retire();
            return true;
        });
        dimensions.entrySet().removeIf(entry -> {
            if (!entry.getValue().equals(view.dimension()) || zones.contains(entry.getKey())) return false;
            tallies.remove(entry.getKey());
            return true;
        });
    }

    List<Grown> goals(ColonyView view) {
        List<Grown> goals = new ArrayList<>();
        harvests.forEach((at, harvest) -> {
            if (at.in(view.level()) && harvest.node().ready(view.level())) goals.add(Grown.of(harvest.node()));
        });
        return List.copyOf(goals);
    }

    List<Line> lines(ZoneView zone) {
        Tally tally = tallies.getOrDefault(zone.id(), new Tally(0, 0, 0));
        List<Line> lines = new ArrayList<>();
        lines.add(Line.text(Component.translatable("folkways.apiary.status.summary", tally.hives(), tally.ripe())));
        if (tally.unsafe() > 0) {
            lines.add(Line.text(Component.translatable("folkways.apiary.status.smoke_missing", tally.unsafe())));
        }
        return List.copyOf(lines);
    }

    void close() {
        campfires.close();
        harvests.values().forEach(harvest -> harvest.node().retire());
        harvests.clear();
        tallies.clear();
        dimensions.clear();
    }
}
