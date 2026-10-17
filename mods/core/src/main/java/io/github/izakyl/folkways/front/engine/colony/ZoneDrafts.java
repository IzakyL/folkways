package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.Delegation;
import io.github.izakyl.folkways.front.api.Shape;
import io.github.izakyl.folkways.front.engine.Enrollments;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class ZoneDrafts {

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
        if (declared.isEmpty() || volume.isEmpty()) {
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
        long volume = (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1)
            * (max.getZ() - min.getZ() + 1);
        if (volume > maxCells) {
            return Set.of();
        }
        Set<BlockPos> cells = new LinkedHashSet<>((int) volume);
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            cells.add(cell.immutable());
        }
        return cells;
    }
}
