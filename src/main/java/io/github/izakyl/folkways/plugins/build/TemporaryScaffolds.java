package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Reader;
import io.github.izakyl.folkways.core.api.persist.Writer;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    List<List<WorldPos>> columns() {
        List<List<WorldPos>> columns = new ArrayList<>();
        for (WorldPos cell : cells) {
            if (cells.contains(cell.at(cell.cell().below()))) {
                continue;
            }
            List<WorldPos> column = new ArrayList<>();
            for (WorldPos next = cell; cells.contains(next); next = next.above()) {
                column.add(next);
            }
            columns.add(List.copyOf(column));
        }
        return columns;
    }

    CompoundTag save() {
        return Writer.of().children("cells", cells, WorldPos::save).tag();
    }

    void load(Reader reader) {
        reader.children("cells").forEach(entry -> WorldPos.load(entry).ifPresent(cells::add));
    }
}
