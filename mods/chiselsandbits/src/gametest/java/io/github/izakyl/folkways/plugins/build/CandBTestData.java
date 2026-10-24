package io.github.izakyl.folkways.plugins.build;

import java.util.Optional;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.registrars.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class CandBTestData {
    private CandBTestData() {
    }

    public static StateEntryStorage sculpture() {
        StateEntryStorage storage = new StateEntryStorage();
        for (int x = 0; x < 7; x++) {
            storage.setBlockInformation(x, 0, 0, new BlockInformation(
                (x < 3 ? Blocks.OAK_PLANKS : Blocks.STONE).defaultBlockState(), Optional.empty()));
        }
        return storage;
    }

    public static BlockState state() {
        return ModBlocks.CHISELED_BLOCK.get().defaultBlockState();
    }

    public static CompoundTag structure(CompoundTag data) {
        CompoundTag tag = new CompoundTag();
        tag.put("size", ints(1, 1, 1));
        ListTag palette = new ListTag();
        palette.add(NbtUtils.writeBlockState(state()));
        tag.put("palette", palette);
        CompoundTag cell = new CompoundTag();
        cell.put("pos", ints(0, 0, 0));
        cell.putInt("state", 0);
        cell.put("nbt", data);
        ListTag blocks = new ListTag();
        blocks.add(cell);
        tag.put("blocks", blocks);
        tag.put("entities", new ListTag());
        return tag;
    }

    private static ListTag ints(int... values) {
        ListTag tag = new ListTag();
        for (int value : values) tag.add(IntTag.valueOf(value));
        return tag;
    }

    public static void sameVoxels(net.minecraft.gametest.framework.GameTestHelper helper,
            StateEntryStorage first, StateEntryStorage second) {
        sameVoxels(helper, first, second, "voxel");
    }

    public static void sameVoxels(net.minecraft.gametest.framework.GameTestHelper helper,
            StateEntryStorage first, StateEntryStorage second, String stage) {
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    helper.assertValueEqual(first.getBlockInformation(x, y, z), second.getBlockInformation(x, y, z),
                        stage + " " + new BlockPos(x, y, z));
                }
            }
        }
    }
}
