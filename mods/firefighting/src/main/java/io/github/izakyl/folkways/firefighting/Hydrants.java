package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.content.equipment.handheld.FireHydrantCabinetBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

// Cabinets announce themselves on their own tick. Responders never enumerate a chunk's other block entities.
public final class Hydrants {
    private static final Map<ServerLevel, Map<Long, LinkedHashMap<BlockPos, FireHydrantCabinetBlockEntity>>> LEVELS = new WeakHashMap<>();

    private static int turn;

    private Hydrants() {
    }

    public static void observe(Level world, BlockPos pos, FireHydrantCabinetBlockEntity cabinet) {
        if (world instanceof ServerLevel level && !cabinet.isRemoved()) {
            LEVELS.computeIfAbsent(level, key -> new LinkedHashMap<>())
                .computeIfAbsent(ChunkPos.asLong(pos), key -> new LinkedHashMap<>()).put(pos, cabinet);
        }
    }

    static List<FireHydrantCabinetBlockEntity> nearby(ServerLevel level, BlockPos fire, int radius) {
        var chunks = LEVELS.get(level);
        if (chunks == null) {
            return List.of();
        }
        List<LinkedHashMap<BlockPos, FireHydrantCabinetBlockEntity>> buckets = new ArrayList<>();
        List<Integer> left = new ArrayList<>();
        for (int x = (fire.getX() - radius) >> 4; x <= (fire.getX() + radius) >> 4; x++) {
            for (int z = (fire.getZ() - radius) >> 4; z <= (fire.getZ() + radius) >> 4; z++) {
                var bucket = chunks.get(ChunkPos.asLong(x, z));
                if (bucket != null && !bucket.isEmpty()) {
                    buckets.add(bucket);
                    left.add(bucket.size());
                }
            }
        }
        List<FireHydrantCabinetBlockEntity> found = new ArrayList<>();
        int first = buckets.isEmpty() ? 0 : Math.floorMod(turn++, buckets.size());
        int checked = 0;
        boolean remaining = true;
        while (checked < 64 && remaining) {
            remaining = false;
            for (int slot = 0; slot < buckets.size() && checked < 64; slot++) {
                int i = (first + slot) % buckets.size();
                if (left.get(i) == 0) {
                    continue;
                }
                remaining = true;
                left.set(i, left.get(i) - 1);
                var bucket = buckets.get(i);
                var entry = bucket.pollFirstEntry();
                var cabinet = entry.getValue();
                checked++;
                if (cabinet.isRemoved() || !level.isLoaded(entry.getKey()) || level.getBlockEntity(entry.getKey()) != cabinet) {
                    continue;
                }
                bucket.putLast(entry.getKey(), cabinet);
                found.add(cabinet);
            }
        }
        found.sort(Comparator.comparingDouble(cabinet -> cabinet.getBlockPos().distSqr(fire)));
        return found;
    }

    static void unload(ServerLevel level, ChunkPos chunk) {
        var chunks = LEVELS.get(level);
        if (chunks != null) {
            chunks.remove(chunk.toLong());
        }
    }

    static void unload(ServerLevel level) { LEVELS.remove(level); }
}
