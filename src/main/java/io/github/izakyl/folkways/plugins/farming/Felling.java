package io.github.izakyl.folkways.plugins.farming;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

// How a wood lot tells a tree, for work that fells trees it never planted.
public final class Felling {

    private static final Habit.Tree TREE = new Habit.Tree(2, 9);

    private Felling() {
    }

    // Every log joined to the trunk that stands on this cell, lowest first; none when no log stands there.
    public static List<BlockPos> tree(LevelReader level, BlockPos foot) {
        return TREE.harvest(null, level, foot);
    }

    // The leaves the cut logs hold up, and no other tree's.
    public static List<BlockPos> canopy(LevelReader level, List<BlockPos> cut) {
        return TREE.canopy(level, cut);
    }

    public static boolean leaf(BlockState state) {
        return Habit.Tree.leaf(state);
    }
}
