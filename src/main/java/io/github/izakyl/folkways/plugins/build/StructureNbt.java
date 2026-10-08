package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.plugins.build.draft.DraftTemplate;
import io.github.izakyl.folkways.plugins.build.draft.Joint;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class StructureNbt {

    private static final String TAG_SIZE = "size";
    private static final String TAG_PALETTE = "palette";
    private static final String TAG_BLOCKS = "blocks";
    private static final String TAG_POS = "pos";
    private static final String TAG_STATE = "state";

    public enum AirMeaning {
        UNCONSTRAINED,
        EXCAVATE
    }

    private StructureNbt() {
    }

    public static Optional<Blueprint> toBlueprint(CompoundTag tag, HolderLookup.Provider registries,
            UUID id, String name, long createdGameTime, AirMeaning airMeaning, int maxCells) {
        if (!tag.contains(TAG_PALETTE, Tag.TAG_LIST) || !tag.contains(TAG_BLOCKS, Tag.TAG_LIST)) {
            return Optional.empty();
        }
        HolderGetter<Block> blocks = registries.lookupOrThrow(Registries.BLOCK);
        ListTag paletteTag = tag.getList(TAG_PALETTE, Tag.TAG_COMPOUND);
        List<Optional<BlockState>> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(BlueprintBlock.read(blocks, paletteTag.getCompound(i)));
        }

        List<BlueprintBlock> cells = new ArrayList<>();
        List<UnloadedBlock> unloaded = new ArrayList<>();
        List<BlockPos> offsets = new ArrayList<>();
        ListTag blockTags = tag.getList(TAG_BLOCKS, Tag.TAG_COMPOUND);
        for (int i = 0; i < blockTags.size(); i++) {
            if (offsets.size() >= maxCells) {
                return Optional.empty();
            }
            CompoundTag cell = blockTags.getCompound(i);
            int index = cell.getInt(TAG_STATE);
            if (index < 0 || index >= palette.size()) {
                continue;
            }
            Optional<BlockState> state = palette.get(index);
            if (state.isPresent() && (state.get().is(Blocks.STRUCTURE_VOID)
                || state.get().isAir() && airMeaning == AirMeaning.UNCONSTRAINED)) {
                continue;
            }
            Optional<BlockPos> offset = readPos(cell);
            if (offset.isEmpty()) {
                continue;
            }
            offsets.add(offset.get());
            if (state.isPresent()) {
                cells.add(new BlueprintBlock(offset.get(), state.get()));
            } else {
                unloaded.add(new UnloadedBlock(offset.get(), paletteTag.getCompound(index), List.of()));
            }
        }
        if (offsets.isEmpty()) {
            return Optional.empty();
        }
        List<Joint> joints = new ArrayList<>();
        ListTag jointTags = tag.getList(DraftTemplate.TAG_JOINTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < jointTags.size(); i++) {
            CompoundTag joint = jointTags.getCompound(i);
            Optional<BlockPos> from = readPos(joint, DraftTemplate.TAG_FROM);
            Optional<BlockPos> to = readPos(joint, DraftTemplate.TAG_TO);
            if (from.isEmpty() || to.isEmpty() || from.get().equals(to.get())
                || !offsets.contains(from.get()) || !offsets.contains(to.get())) {
                return Optional.empty();
            }
            joints.add(new Joint(from.get(), to.get()));
        }
        return readVolume(tag, offsets)
            .filter(volume -> volume.holdsAll(offsets))
            .map(volume -> new Blueprint(id, name, cells, unloaded, joints, volume, createdGameTime));
    }

    private static Optional<BlueprintVolume> readVolume(CompoundTag tag, List<BlockPos> cells) {
        if (tag.contains(TAG_SIZE, Tag.TAG_LIST)) {
            ListTag size = tag.getList(TAG_SIZE, Tag.TAG_INT);
            if (size.size() == 3) {
                return BlueprintVolume.of(size.getInt(0), size.getInt(1), size.getInt(2));
            }
        }
        return BlueprintVolume.around(cells);
    }

    private static Optional<BlockPos> readPos(CompoundTag cell) {
        return readPos(cell, TAG_POS);
    }

    private static Optional<BlockPos> readPos(CompoundTag cell, String key) {
        if (!cell.contains(key, Tag.TAG_LIST)) {
            return Optional.empty();
        }
        ListTag pos = cell.getList(key, Tag.TAG_INT);
        if (pos.size() != 3) {
            return Optional.empty();
        }
        return Optional.of(new BlockPos(pos.getInt(0), pos.getInt(1), pos.getInt(2)));
    }
}
