package io.github.izakyl.folkways.plugins.build.draft;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class DraftTemplate {

    private static final String TAG_SIZE = "size";
    private static final String TAG_PALETTE = "palette";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_POS = "pos";
    private static final String TAG_STATE = "state";
    private static final String TAG_DATA_VERSION = "DataVersion";

    private static final String TAG_PALETTES = "palettes";

    public static final String TAG_JOINTS = "joints";
    public static final String TAG_FROM = "from";
    public static final String TAG_TO = "to";

    private DraftTemplate() {
    }

    public static Piece pieceOf(CompoundTag file) {
        ListTag measured = file.getList(TAG_SIZE, Tag.TAG_INT);
        if (measured.size() != 3) {
            throw new IllegalArgumentException("a structure file says how big it is in three numbers");
        }
        int width = measured.getInt(0);
        int height = measured.getInt(1);
        int depth = measured.getInt(2);
        List<BlockState> palette = new ArrayList<>();
        ListTag states = file.contains(TAG_PALETTE, Tag.TAG_LIST)
            ? file.getList(TAG_PALETTE, Tag.TAG_COMPOUND)
            : file.getList(TAG_PALETTES, Tag.TAG_LIST).getList(0);
        for (int index = 0; index < states.size(); index++) {
            palette.add(NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), states.getCompound(index)));
        }
        if (palette.isEmpty()) {
            throw new IllegalArgumentException("a structure file names the blocks it is made of");
        }
        Map<BlockPos, BlockState> cells = new LinkedHashMap<>();
        ListTag blocks = file.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < blocks.size(); index++) {
            CompoundTag written = blocks.getCompound(index);
            ListTag at = written.getList(TAG_POS, Tag.TAG_INT);
            int state = written.getInt(TAG_STATE);
            if (at.size() != 3 || state < 0 || state >= palette.size()) {
                continue;
            }
            BlockState block = palette.get(state);
            if (block.isAir() || block.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            cells.put(new BlockPos(at.getInt(0), at.getInt(1), depth - 1 - at.getInt(2)), block);
        }
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("this structure file holds no block at all");
        }
        return new Piece(cells, new Vec3i(width, height, depth));
    }

    public static CompoundTag of(Draft draft, int dataVersion) {
        CompoundTag file = new CompoundTag();
        file.putInt(TAG_DATA_VERSION, dataVersion);
        file.put(TAG_SIZE, sizeOf(draft.extent().size()));

        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        List<BlockState> order = new ArrayList<>();
        ListTag blocks = new ListTag();
        for (Draft.Cell cell : draft.cells()) {
            BlockState state = cell.state();
            Integer index = palette.get(state);
            if (index == null) {
                index = order.size();
                palette.put(state, index);
                order.add(state);
            }
            CompoundTag written = new CompoundTag();
            written.put(TAG_POS, posOf(cell.offset()));
            written.putInt(TAG_STATE, index);
            blocks.add(written);
        }
        if (order.isEmpty()) {
            order.add(Blocks.AIR.defaultBlockState());
        }
        ListTag states = new ListTag();
        for (BlockState state : order) {
            states.add(NbtUtils.writeBlockState(state));
        }
        file.put(TAG_PALETTE, states);
        file.put(TAG_BLOCKS, blocks);
        if (!draft.joints().isEmpty()) {
            ListTag joints = new ListTag();
            for (Joint joint : draft.joints()) {
                CompoundTag written = new CompoundTag();
                written.put(TAG_FROM, posOf(joint.from()));
                written.put(TAG_TO, posOf(joint.to()));
                joints.add(written);
            }
            file.put(TAG_JOINTS, joints);
        }
        return file;
    }

    private static ListTag sizeOf(Vec3i size) {
        return threeInts(size.getX(), size.getY(), size.getZ());
    }

    private static ListTag posOf(BlockPos offset) {
        return threeInts(offset.getX(), offset.getY(), offset.getZ());
    }

    private static ListTag threeInts(int x, int y, int z) {
        ListTag written = new ListTag();
        written.add(IntTag.valueOf(x));
        written.add(IntTag.valueOf(y));
        written.add(IntTag.valueOf(z));
        return written;
    }
}
