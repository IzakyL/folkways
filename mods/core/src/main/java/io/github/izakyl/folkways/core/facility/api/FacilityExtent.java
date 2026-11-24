package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** A union of block-space elements and independently addressed entities. */
public record FacilityExtent(Set<Element> elements) {

    public static final FacilityExtent EMPTY = new FacilityExtent(Set.of());

    public sealed interface Element permits Block, Box, Entity {
    }

    public record Block(WorldPos pos) implements Element {
    }

    public record Box(Realm realm, BlockPos min, BlockPos max) implements Element {

        public Box {
            Objects.requireNonNull(realm);
            min = min.immutable();
            max = max.immutable();
            if (min.getX() > max.getX() || min.getY() > max.getY() || min.getZ() > max.getZ()) {
                throw new IllegalArgumentException("extent");
            }
        }

        public BoundingBox bounds() {
            return BoundingBox.fromCorners(min, max);
        }

        public boolean contains(WorldPos pos) {
            return realm.equals(pos.realm()) && bounds().isInside(pos.cell());
        }

        private boolean loaded(ServerLevel level) {
            WorldPos first = new WorldPos(realm, min);
            return WorldSpaces.storage(level, first).filter(level::isLoaded).isPresent()
                && WorldSpaces.storage(level, first.at(max)).filter(level::isLoaded).isPresent()
                && chunksLoaded(level, first.block(level), first.at(max).block(level));
        }
    }

    public record Entity(UUID id) implements Element {
    }

    public FacilityExtent {
        elements = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(elements));
    }

    public FacilityExtent(Realm realm, BlockPos min, BlockPos max) {
        this(Set.of(new Box(realm, min, max)));
    }

    public static FacilityExtent blocks(Collection<WorldPos> cells) {
        Set<Element> elements = new LinkedHashSet<>();
        cells.forEach(cell -> elements.add(new Block(cell)));
        return new FacilityExtent(elements);
    }

    public static Optional<FacilityExtent> structure(ServerLevel level, UUID id) {
        return WorldSpaces.frame(level, id).flatMap(WorldSpaces.Frame::bounds).map(box ->
            new FacilityExtent(new Realm.Frame(id), new BlockPos(box.minX(), box.minY(), box.minZ()),
                new BlockPos(box.maxX(), box.maxY(), box.maxZ())));
    }

    public Optional<WorldPos> firstBlock() {
        for (Element element : elements) {
            switch (element) {
                case Block block -> {
                    return Optional.of(block.pos());
                }
                case Box box -> {
                    return Optional.of(new WorldPos(box.realm(), box.min()));
                }
                case Entity ignored -> { }
            }
        }
        return Optional.empty();
    }

    public boolean contains(WorldPos pos) {
        return elements.contains(new Block(pos)) || elements.stream().anyMatch(element ->
            element instanceof Box box && box.contains(pos));
    }

    public boolean contains(UUID id) {
        return elements.contains(new Entity(id));
    }

    public boolean overlaps(FacilityExtent other) {
        return elements.stream().anyMatch(element -> switch (element) {
            case Block block -> other.contains(block.pos());
            case Entity entity -> other.contains(entity.id());
            case Box box -> other.elements.stream().anyMatch(candidate -> switch (candidate) {
                case Block block -> box.contains(block.pos());
                case Box bounds -> box.realm().equals(bounds.realm()) && box.bounds().intersects(bounds.bounds());
                case Entity ignored -> false;
            });
        });
    }

    /** Rendering bounds in a single realm. Ownership queries use the elements directly. */
    public Optional<Box> bounds(Realm realm) {
        return elements.stream().flatMap(element -> switch (element) {
            case Block block when block.pos().realm().equals(realm) ->
                Stream.of(new Box(realm, block.pos().cell(), block.pos().cell()));
            case Box box when box.realm().equals(realm) -> Stream.of(box);
            default -> Stream.empty();
        }).reduce((a, b) -> new Box(realm, BlockPos.min(a.min(), b.min()), BlockPos.max(a.max(), b.max())));
    }

    public List<WorldPos> cells() {
        return elements.stream().flatMap(element -> switch (element) {
            case Block block -> Stream.of(block.pos());
            case Box box -> BlockPos.betweenClosedStream(box.min(), box.max())
                .map(pos -> new WorldPos(box.realm(), pos));
            case Entity ignored -> Stream.empty();
        }).distinct().toList();
    }

    public boolean loaded(ServerLevel level) {
        return elements.stream().allMatch(element -> switch (element) {
            case Block block -> WorldSpaces.storage(level, block.pos()).filter(level::isLoaded).isPresent();
            case Box box -> box.loaded(level);
            case Entity entity -> level.getEntity(entity.id()) != null;
        });
    }

    private static boolean chunksLoaded(ServerLevel level, BlockPos start, BlockPos end) {
        for (int x = start.getX() >> 4; x <= end.getX() >> 4; x++) {
            for (int z = start.getZ() >> 4; z <= end.getZ() >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }
}
