package io.github.izakyl.folkways.core.api.colony;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

// What a colony holds, by its shape alone. The core keeps where it lies and whom it is held for; what it means
// is the business of whoever holds it.
public sealed interface Held {

    // Every cell it lies on; an entity lies on none, it goes where it goes.
    List<WorldPos> cells();

    // The same thing after the cells in `moved` were carried along with their realm.
    Held moved(Map<WorldPos, WorldPos> moved);

    // An entity, and the type it was when taken up, so it can be known while it is not loaded.
    record Entity(UUID entity, ResourceLocation type) implements Held {

        public Entity {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(type, "type");
        }

        public static Entity of(net.minecraft.world.entity.Entity entity) {
            return new Entity(entity.getUUID(), BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        }

        @Override
        public List<WorldPos> cells() {
            return List.of();
        }

        @Override
        public Held moved(Map<WorldPos, WorldPos> moved) {
            return this;
        }
    }

    record Block(WorldPos cell) implements Held {

        public Block {
            Objects.requireNonNull(cell, "cell");
        }

        @Override
        public List<WorldPos> cells() {
            return List.of(cell);
        }

        @Override
        public Held moved(Map<WorldPos, WorldPos> moved) {
            return new Block(moved.getOrDefault(cell, cell));
        }
    }

    record Area(Set<WorldPos> covered) implements Held {

        public Area {
            if (covered.isEmpty()) {
                throw new IllegalArgumentException("an area covering no cells is not an area");
            }
            covered = Collections.unmodifiableSet(new LinkedHashSet<>(covered));
        }

        @Override
        public List<WorldPos> cells() {
            return List.copyOf(covered);
        }

        @Override
        public Held moved(Map<WorldPos, WorldPos> moved) {
            Set<WorldPos> next = new LinkedHashSet<>(covered.size());
            covered.forEach(cell -> next.add(moved.getOrDefault(cell, cell)));
            return new Area(next);
        }
    }

    record Line(List<WorldPos> points) implements Held {

        public Line {
            if (points.size() < 2) {
                throw new IllegalArgumentException("a line of fewer than two points is not a line");
            }
            points = List.copyOf(points);
        }

        @Override
        public List<WorldPos> cells() {
            return points;
        }

        @Override
        public Held moved(Map<WorldPos, WorldPos> moved) {
            List<WorldPos> next = new ArrayList<>(points.size());
            points.forEach(point -> next.add(moved.getOrDefault(point, point)));
            return new Line(next);
        }
    }
}
