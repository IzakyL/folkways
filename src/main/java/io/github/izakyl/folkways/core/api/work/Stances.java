package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public sealed interface Stances {

    Stances WHEREVER = new Wherever();

    record Cells(Set<WorldPos> cells) implements Stances {

        public Cells {
            cells = Set.copyOf(cells);
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("a node with no footing is not a node");
            }
        }
    }

    record Wherever() implements Stances {
    }

    default Set<WorldPos> cells() {
        return this instanceof Cells(Set<WorldPos> held) ? held : Set.of();
    }

    static Optional<Stances> of(Realm realm, Collection<BlockPos> cells) {
        if (cells.isEmpty()) {
            return Optional.empty();
        }
        Set<WorldPos> held = new LinkedHashSet<>(cells.size());
        for (BlockPos cell : cells) {
            held.add(new WorldPos(realm, cell));
        }
        return Optional.of(new Cells(held));
    }

    static Optional<Stances> of(Level level, Collection<BlockPos> cells) {
        return of(cells.stream().map(cell -> WorldPos.of(level, cell)).toList());
    }

    static Optional<Stances> of(Collection<WorldPos> cells) {
        return cells.isEmpty() ? Optional.empty() : Optional.of(new Cells(Set.copyOf(cells)));
    }

    static Stances at(WorldPos cell, WorldPos... more) {
        Set<WorldPos> held = new LinkedHashSet<>(more.length + 1);
        held.add(cell);
        java.util.Collections.addAll(held, more);
        return new Cells(held);
    }

    static Stances at(Level level, BlockPos cell) {
        return new Cells(Set.of(WorldPos.of(level, cell)));
    }
}
