package io.github.izakyl.folkways.core.engine.travel.local;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

record Section(boolean known, LongSet stands, Long2ObjectMap<long[]> steps) {

    static final int SHIFT = 3;

    static final int EDGE = 1 << SHIFT;

    static final int READ_ACROSS = 2;

    static final int READ_BELOW = 5;

    static final int READ_ABOVE = 4;

    static final Section UNKNOWN = new Section(false, LongSets.EMPTY_SET, Long2ObjectMaps.emptyMap());

    static long keyOf(int x, int y, int z) {
        return BlockPos.asLong(x >> SHIFT, y >> SHIFT, z >> SHIFT);
    }

    static long keyOf(long cell) {
        return keyOf(BlockPos.getX(cell), BlockPos.getY(cell), BlockPos.getZ(cell));
    }

    static BlockPos origin(long key) {
        return new BlockPos(BlockPos.getX(key) << SHIFT, BlockPos.getY(key) << SHIFT,
            BlockPos.getZ(key) << SHIFT);
    }

    boolean stepsTo(long from, long to) {
        long[] out = steps.get(from);
        if (out == null) {
            return false;
        }
        for (long each : out) {
            if (each == to) {
                return true;
            }
        }
        return false;
    }

    static void touchedBy(BlockPos cell, LongSet into) {
        for (int sx = (cell.getX() - READ_ACROSS) >> SHIFT; sx <= (cell.getX() + READ_ACROSS) >> SHIFT; sx++) {
            for (int sy = (cell.getY() - READ_ABOVE) >> SHIFT; sy <= (cell.getY() + READ_BELOW) >> SHIFT; sy++) {
                for (int sz = (cell.getZ() - READ_ACROSS) >> SHIFT; sz <= (cell.getZ() + READ_ACROSS) >> SHIFT; sz++) {
                    into.add(BlockPos.asLong(sx, sy, sz));
                }
            }
        }
    }
}
