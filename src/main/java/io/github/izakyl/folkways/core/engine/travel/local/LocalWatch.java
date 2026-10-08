package io.github.izakyl.folkways.core.engine.travel.local;

import io.github.izakyl.folkways.core.api.ground.Ground;
import io.github.izakyl.folkways.core.api.ground.Grounds;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class LocalWatch implements Grounds.Watch {

    private final ServerLevel level;
    private final BlockPos min;
    private final BlockPos max;
    private final Map<Walker.Kind, Long2ObjectOpenHashMap<Section>> surveyed = new HashMap<>();

    LocalWatch(ServerLevel level, BoundingBox box) {
        this.level = level;
        this.min = new BlockPos(box.minX(), box.minY(), box.minZ()).offset(-Section.EDGE, -Section.EDGE, -Section.EDGE);
        this.max = new BlockPos(box.maxX(), box.maxY(), box.maxZ()).offset(Section.EDGE, Section.EDGE, Section.EDGE);
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
        if (!covers(cell.getX(), cell.getY(), cell.getZ())) {
            return;
        }
        LongOpenHashSet touched = new LongOpenHashSet();
        Section.touchedBy(cell, touched);
        for (Long2ObjectOpenHashMap<Section> sections : surveyed.values()) {
            touched.forEach(sections::remove);
        }
    }

    Section section(Walker walker, long key) {
        Long2ObjectOpenHashMap<Section> sections =
            surveyed.computeIfAbsent(walker.kind(), kind -> new Long2ObjectOpenHashMap<>());
        Section known = sections.get(key);
        if (known != null) {
            return known;
        }
        Section read = Survey.of(level, key, walker, Map.of());
        if (read.known()) {
            sections.put(key, read);
        }
        return read;
    }

    @Override
    public Ground now(Collection<Mob> walkers) {
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
    public void close() {
        surveyed.clear();
        LocalGrounds.forget(this);
    }
}
