package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Reader;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

final class TemporaryScaffolds {

    private final Set<WorldPos> cells = new LinkedHashSet<>();
    private final Runnable dirty;

    TemporaryScaffolds(Runnable dirty) {
        this.dirty = dirty;
    }

    boolean contains(WorldPos cell) {
        return cells.contains(cell);
    }

    boolean isEmpty() {
        return cells.isEmpty();
    }

    Set<WorldPos> cells() {
        return Set.copyOf(cells);
    }

    void added(Collection<WorldPos> added) {
        cells.addAll(added);
        dirty.run();
    }

    void removed(Collection<WorldPos> removed) {
        cells.removeAll(removed);
        dirty.run();
    }

    void reconcile(ServerLevel level) {
        if (cells.removeIf(cell -> cell.in(level) && level.hasChunkAt(cell.block(level))
            && !level.getBlockState(cell.block(level)).is(Blocks.SCAFFOLDING))) {
            dirty.run();
        }
    }

    // A horizontal deck and its access columns must be cleaned up together.
    List<List<WorldPos>> structures() {
        Set<WorldPos> remaining = new LinkedHashSet<>(cells);
        List<List<WorldPos>> structures = new ArrayList<>();
        Comparator<WorldPos> order = Comparator.comparingInt((WorldPos cell) -> cell.cell().getY())
            .thenComparingInt(cell -> cell.cell().getX()).thenComparingInt(cell -> cell.cell().getZ());
        for (WorldPos start : cells) {
            if (!remaining.remove(start)) {
                continue;
            }
            ArrayDeque<WorldPos> queue = new ArrayDeque<>();
            List<WorldPos> structure = new ArrayList<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                WorldPos cell = queue.removeFirst();
                structure.add(cell);
                for (Direction direction : Direction.values()) {
                    WorldPos next = cell.at(cell.cell().relative(direction));
                    if (remaining.remove(next)) {
                        queue.addLast(next);
                    }
                }
            }
            structure.sort(order);
            structures.add(List.copyOf(structure));
        }
        return List.copyOf(structures);
    }

    CompoundTag save() {
        return Writer.of().children("cells", cells, WorldPos::save).tag();
    }

    void load(Reader reader) {
        reader.requiredChildren("cells").forEach(entry -> WorldPos.load(entry).ifPresent(cells::add));
    }
}
