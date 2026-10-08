package io.github.izakyl.folkways.core.api.terms;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;

public final class Loaded {

    private Loaded() {
    }

    public static boolean around(LevelReader level, BlockPos cell, int reach) {
        return level.hasChunksAt(cell.getX() - reach, cell.getY(), cell.getZ() - reach,
            cell.getX() + reach, cell.getY(), cell.getZ() + reach);
    }
}
