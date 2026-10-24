package io.github.izakyl.folkways.plugins.farming;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;

// How a wood lot tells a tree, for work that fells trees it never planted.
public final class Felling {

    private static final Habit.Tree TREE = new Habit.Tree(2, 9);

    private static final List<java.util.function.Predicate<BlockState>> ATTACHMENTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void registerAttachment(java.util.function.Predicate<BlockState> attachment) {
        ATTACHMENTS.add(attachment);
    }

    public static boolean attachment(BlockState state) {
        return ATTACHMENTS.stream().anyMatch(rule -> rule.test(state));
    }

    // Gather hanging plants before removing the leaves that support them. Bottom first prevents
    // neighbor updates from deleting the remaining vine before its native loot can be collected.
    static List<BlockPos> withAttachments(LevelReader level, List<BlockPos> supports, List<BlockPos> leaves) {
        java.util.Set<BlockPos> hanging = new java.util.LinkedHashSet<>();
        java.util.List<BlockPos> anchors = new java.util.ArrayList<>(supports);
        anchors.addAll(leaves);
        for (BlockPos anchor : anchors) {
            BlockPos below = anchor.below();
            for (int depth = 0; depth < 64 && level.hasChunkAt(below) && attachment(level.getBlockState(below)); depth++) {
                hanging.add(below.immutable());
                below = below.below();
            }
        }
        java.util.List<BlockPos> result = new java.util.ArrayList<>(hanging);
        result.sort(java.util.Comparator.comparingInt(BlockPos::getY));
        result.addAll(leaves);
        return List.copyOf(result);
    }

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
