package io.github.izakyl.folkways.plugins.build.draft;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record Growth(int round, CompoundTag kept, BoundingBox reach) {

    public Growth {
        if (round < 0) {
            throw new IllegalArgumentException("a round counts up from zero");
        }
        kept = Objects.requireNonNull(kept, "kept").copy();
        Objects.requireNonNull(reach, "reach");
    }

    public static Growth first(Hint hint) {
        return new Growth(0, new CompoundTag(), hint.bounds());
    }

    public Growth next(CompoundTag keeping, BlockPos corner, Draft draft, BlockPos anchor) {
        BlockPos low = corner.subtract(anchor);
        BlockPos high = low.offset(draft.extent().size()).offset(-1, -1, -1);
        BoundingBox grown = new BoundingBox(Math.min(reach.minX(), low.getX()), Math.min(reach.minY(), low.getY()),
            Math.min(reach.minZ(), low.getZ()), Math.max(reach.maxX(), high.getX()),
            Math.max(reach.maxY(), high.getY()), Math.max(reach.maxZ(), high.getZ()));
        return new Growth(round + 1, keeping, grown);
    }
}
