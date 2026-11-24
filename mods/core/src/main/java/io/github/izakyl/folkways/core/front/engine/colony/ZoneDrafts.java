package io.github.izakyl.folkways.core.front.engine.colony;

import net.minecraft.network.chat.Component;
import java.util.function.Predicate;
import java.util.Map;
import java.util.LinkedHashMap;
import io.github.izakyl.folkways.core.facility.api.Facilities;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.Delegation;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class ZoneDrafts {

    private static final Map<ResourceLocation, Predicate<ColonySettings>> DEMOLITIONS = new LinkedHashMap<>();

    public static void demolition(ResourceLocation delegation, Predicate<ColonySettings> settings) {
        DEMOLITIONS.put(delegation, settings);
    }

    public static List<Facilities.Source> affected(ServerLevel level, ResourceLocation delegation, BlockPos first,
            BlockPos second, ColonySettings chosen) {
        var test = DEMOLITIONS.get(delegation);
        var declared = Enrollments.delegation(delegation).orElse(null);
        if (test == null || declared == null || declared.disabledReason().isPresent()
                || !(declared.shape() instanceof Shape.Volume volume) || !test.test(settled(declared, chosen))) {
            return List.of();
        }
        return Facilities.affected(level, cellsBetween(first, second, volume.maxCells()));
    }

    public static Component describeAffected(ServerLevel level, List<Facilities.Source> affected) {
        var text = Component.translatable("folkways.preview.affected_facilities");
        for (var source : affected) {
            BlockPos anchor = source.anchor().block(level);
            text.append("\n").append(level.getBlockState(anchor).getBlock().getName())
                .append(" (" + anchor.toShortString() + ")");
        }
        return text;
    }

    private ZoneDrafts() {
    }

    public static Optional<Shape.Volume> volumeOf(ResourceLocation delegation) {
        return Enrollments.delegation(delegation)
            .map(Delegation::shape)
            .filter(Shape.Volume.class::isInstance)
            .map(Shape.Volume.class::cast);
    }

    public static Optional<ColonyZone> zone(ServerLevel level, Colony colony,
            ResourceLocation delegation, BlockPos first, BlockPos second, ColonySettings chosen) {
        Optional<Delegation> declared = Enrollments.delegation(delegation);
        Optional<Shape.Volume> volume = volumeOf(delegation);
        if (declared.isEmpty() || volume.isEmpty() || declared.get().disabledReason().isPresent()) {
            return Optional.empty();
        }
        Set<BlockPos> cells = cellsBetween(first, second, volume.get().maxCells());
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        return ColonyFront.of(colony).addZone(level, delegation, cells, settled(declared.get(), chosen));
    }

    public static Optional<ColonyPath> path(ServerLevel level, Colony colony,
            ResourceLocation delegation, List<BlockPos> points, ColonySettings chosen) {
        Optional<Delegation> declared = Enrollments.delegation(delegation);
        if (declared.isEmpty() || !(declared.get().shape() instanceof Shape.Path line)
                || declared.get().disabledReason().isPresent()
                || points.size() < 2 || points.size() > line.maxPoints()) {
            return Optional.empty();
        }
        return ColonyFront.of(colony).addPath(level, delegation, points, settled(declared.get(), chosen));
    }

    // What the player chose, over the delegation's defaults, held to its schema.
    private static ColonySettings settled(Delegation declared, ColonySettings chosen) {
        ColonySettings settled = ColonySettings.byDefault(declared.schema());
        for (String key : chosen.keys()) {
            settled = settled.with(key, chosen.valueOf(key).orElseThrow());
        }
        return settled.conformedTo(declared.schema());
    }

    private static Set<BlockPos> cellsBetween(BlockPos first, BlockPos second, int maxCells) {
        BlockPos min = BlockPos.min(first, second);
        BlockPos max = BlockPos.max(first, second);
        long x = (long) max.getX() - min.getX() + 1;
        long y = (long) max.getY() - min.getY() + 1;
        long z = (long) max.getZ() - min.getZ() + 1;
        if (x > maxCells || y > maxCells || z > maxCells || x * y > maxCells || x * y * z > maxCells) {
            return Set.of();
        }
        long volume = x * y * z;
        if (volume == 0) {
            return Set.of();
        }
        Set<BlockPos> cells = new LinkedHashSet<>((int) volume);
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            cells.add(cell.immutable());
        }
        return cells;
    }
}
