package io.github.izakyl.folkways.create.elevator;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import io.github.izakyl.folkways.core.api.terms.Footing;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** The lowest connected, unobstructed native deck; seats and roofs do not define passenger space. */
final class ElevatorDeck {
    private ElevatorDeck() { }

    static Set<BlockPos> footings(AbstractContraptionEntity vehicle) {
        var contraption = vehicle.getContraption();
        var blocks = contraption.getContraptionWorld();
        Set<BlockPos> candidates = new HashSet<>();
        for (BlockPos support : contraption.getBlocks().keySet()) {
            BlockPos feet = support.above();
            if (Footing.withFeetAt(blocks, feet)) candidates.add(feet);
        }
        if (candidates.isEmpty()) return Set.of();
        BlockPos first = candidates.stream().min(Comparator.<BlockPos>comparingInt(BlockPos::getY)
            .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).orElseThrow();
        Set<BlockPos> connected = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        connected.add(first); queue.add(first);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.remove();
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos next = cell.relative(side);
                if (candidates.contains(next) && connected.add(next)) queue.add(next);
            }
        }
        return Set.copyOf(connected);
    }
}
