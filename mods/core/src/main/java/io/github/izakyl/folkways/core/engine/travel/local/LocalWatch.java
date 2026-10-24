package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

final class LocalWatch implements Grounds.Watch {

    private static final int MOST_GEOMETRIES = 48;
    private static final int MOST_ACCESS = 32;
    private static final int MOST_CACHED_CELLS = 131072;
    private static final int MOST_SECTIONS = 512;

    private final ServerLevel level;
    private final BlockPos min;
    private final BlockPos max;
    private final Map<GeometryKey, Geometry> geometries = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<AccessKey, LocalAccess> accesses = new LinkedHashMap<>(16, 0.75F, true);
    private final AABB readArea;
    private LongOpenHashSet loaded;
    private List<AABB> obstacles;
    private long checkedAt = Long.MIN_VALUE;
    private long keepouts = Keepouts.revision();
    private long revision;
    private boolean closed;
    private long accessRequests;
    private long accessHits;
    private long sectionBuilds;
    private long work;
    private long invalidations;
    private long environmentScans;

    LocalWatch(ServerLevel level, BoundingBox box) {
        this.level = level;
        min = new BlockPos(box.minX(), box.minY(), box.minZ()).offset(-Section.EDGE, -Section.EDGE, -Section.EDGE);
        max = new BlockPos(box.maxX(), box.maxY(), box.maxZ()).offset(Section.EDGE, Section.EDGE, Section.EDGE);
        BlockPos first = Section.origin(Section.keyOf(min.asLong()));
        BlockPos last = Section.origin(Section.keyOf(max.asLong()))
            .offset(Section.EDGE - 1, Section.EDGE - 1, Section.EDGE - 1);
        readArea = AABB.encapsulatingFullBlocks(first.offset(-Section.READ_ACROSS, -Section.READ_BELOW,
            -Section.READ_ACROSS), last.offset(Section.READ_ACROSS, Section.READ_ABOVE, Section.READ_ACROSS));
    }

    ServerLevel level() {
        return level;
    }

    BlockPos min() {
        return min;
    }

    BlockPos max() {
        return max;
    }

    boolean covers(int x, int y, int z) {
        return x >= min.getX() && x <= max.getX() && y >= min.getY() && y <= max.getY()
            && z >= min.getZ() && z <= max.getZ();
    }

    void changed(BlockPos cell) {
        // A surveyed border section reads outside the nominal watch too.
        if (!readArea.contains(cell.getX() + 0.5D, cell.getY() + 0.5D, cell.getZ() + 0.5D)) {
            return;
        }
        LongOpenHashSet touched = new LongOpenHashSet();
        Section.touchedBy(cell, touched);
        for (Map.Entry<GeometryKey, Geometry> each : geometries.entrySet()) {
            if (each.getKey().changes().isEmpty()) {
                touched.forEach(each.getValue().sections::remove);
            }
        }
        invalidate(false);
    }

    Geometry geometry(Walker walker, Map<BlockPos, BlockState> changes) {
        GeometryKey key = new GeometryKey(walker.kind(), changes, railOverlayOrigin(walker, changes));
        Geometry found = geometries.computeIfAbsent(key, ignored -> new Geometry(walker, changes));
        trim(geometries, MOST_GEOMETRIES);
        return found;
    }

    Ground.Access access(LocalGround ground, @Nullable Walker walker, Map<BlockPos, BlockState> changes,
                         Collection<BlockPos> exits) {
        accessRequests++;
        List<BlockPos> sorted = exits.stream().map(BlockPos::immutable).distinct()
            .sorted(BlockPos::compareTo).toList();
        AccessKey key = new AccessKey(walker == null ? null : walker.kind(), changes, sorted,
            walker == null ? null : railOverlayOrigin(walker, changes));
        LocalAccess found = accesses.get(key);
        if (found != null && found.valid()) {
            accessHits++;
            return found;
        }
        found = new LocalAccess(this, ground, walker, sorted);
        accesses.put(key, found);
        trimAccesses();
        return found;
    }

    void worked(int amount) {
        work += amount;
        trimAccesses();
    }

    private void trimAccesses() {
        int cells = accesses.values().stream().mapToInt(LocalAccess::visited).sum();
        var iterator = accesses.entrySet().iterator();
        while ((accesses.size() > MOST_ACCESS || cells > MOST_CACHED_CELLS) && iterator.hasNext()) {
            cells -= iterator.next().getValue().visited();
            iterator.remove();
        }
    }

    private static <K, V> void trim(Map<K, V> cache, int most) {
        var iterator = cache.entrySet().iterator();
        while (cache.size() > most && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private void invalidate(boolean allGeometry) {
        revision++;
        invalidations++;
        accesses.clear();
        if (allGeometry) {
            geometries.clear();
        } else {
            geometries.keySet().removeIf(key -> !key.changes().isEmpty());
        }
    }

    // Chunk availability and moving barriers can change without a block write. Check once per tick,
    // and again for a fresh body snapshot immediately before a caller commits a placement.
    private void environment(boolean force) {
        if (closed) {
            return;
        }
        if (!force && checkedAt == level.getGameTime() && keepouts == Keepouts.revision()) {
            return;
        }
        checkedAt = level.getGameTime();
        Keepouts.refresh(level);
        long rules = Keepouts.revision();
        if (keepouts != rules) {
            keepouts = rules;
            invalidate(true);
        }
        environmentScans++;
        LongOpenHashSet now = new LongOpenHashSet();
        for (int x = ((int) Math.floor(readArea.minX)) >> 4; x <= (((int) Math.ceil(readArea.maxX) - 1) >> 4); x++) {
            for (int z = ((int) Math.floor(readArea.minZ)) >> 4; z <= (((int) Math.ceil(readArea.maxZ) - 1) >> 4); z++) {
                if (level.hasChunk(x, z)) {
                    now.add(ChunkPos.asLong(x, z));
                }
            }
        }
        List<AABB> occupied = Structures.obstacles(level, readArea);
        if (loaded != null && (!loaded.equals(now) || !obstacles.equals(occupied))) {
            invalidate(true);
        }
        loaded = now;
        obstacles = occupied;
    }

    @Override
    public long revision() {
        environment(false);
        return revision;
    }

    @Override
    public long refresh() {
        environment(true);
        return revision;
    }

    boolean valid(long before) {
        return !closed && revision() == before;
    }

    @Override
    public Grounds.Stats stats() {
        return new Grounds.Stats(accessRequests, accessHits, sectionBuilds, work, invalidations, environmentScans,
            accesses.size(), geometries.size(), accesses.values().stream().mapToInt(LocalAccess::visited).sum());
    }

    @Override
    public Ground now(Collection<Mob> walkers) {
        environment(false);
        Map<Walker.Kind, Walker> kinds = new LinkedHashMap<>();
        List<Mob> bodies = new ArrayList<>();
        for (Mob body : walkers) {
            if (body.isRemoved() || body.level() != level) {
                continue;
            }
            bodies.add(body);
            Walker.of(body).ifPresent(walker -> kinds.putIfAbsent(walker.kind(), walker));
        }
        return new LocalGround(this, List.copyOf(kinds.values()), List.copyOf(bodies), Map.of());
    }

    @Override
    public Ground now(LivingEntity body) {
        if (body instanceof Mob mob && body.getPose() != net.minecraft.world.entity.Pose.SLEEPING) {
            return now(List.of(mob));
        }
        environment(false);
        return new LocalGround(this, Walker.of(body).stream().toList(),
            body instanceof Mob mob ? List.of(mob) : List.of(), Map.of());
    }

    @Override
    public void close() {
        closed = true;
        invalidate(true);
        LocalGrounds.forget(this);
    }

    @Nullable
    private static BlockPos railOverlayOrigin(Walker walker, Map<BlockPos, BlockState> changes) {
        return changes.values().stream().anyMatch(state -> state.is(BlockTags.RAILS))
            ? walker.source().blockPosition().immutable() : null;
    }

    private record GeometryKey(Walker.Kind kind, Map<BlockPos, BlockState> changes, @Nullable BlockPos railOrigin) {
    }

    private record AccessKey(@Nullable Walker.Kind kind, Map<BlockPos, BlockState> changes, List<BlockPos> exits,
                             @Nullable BlockPos railOrigin) {
    }

    final class Geometry {
        private final Walker walker;
        private final Map<BlockPos, BlockState> changes;
        private final Long2ObjectLinkedOpenHashMap<Survey.Read> sections = new Long2ObjectLinkedOpenHashMap<>();

        Geometry(Walker walker, Map<BlockPos, BlockState> changes) {
            this.walker = walker;
            this.changes = changes;
        }

        Survey.Read read(long key) {
            Survey.Read read = sections.getAndMoveToLast(key);
            if (read == null) {
                read = new Survey.Read(level, key, walker, changes);
                sections.putAndMoveToLast(key, read);
                sectionBuilds++;
                while (sections.size() > MOST_SECTIONS) {
                    sections.removeFirst();
                }
            }
            return read;
        }
    }
}
