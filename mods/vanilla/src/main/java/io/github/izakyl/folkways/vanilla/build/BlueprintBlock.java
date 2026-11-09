package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.base.persist.Writer;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

public record BlueprintBlock(BlockPos offset, BlockState state, BlockData data) {

    static final String TAG_OFFSET = "offset";
    static final String TAG_STATE = "state";
    static final String TAG_DATA = "blockEntity";

    public BlueprintBlock {
        offset = offset.immutable();
    }

    public BlueprintBlock(BlockPos offset, BlockState state) {
        this(offset, state, BlockData.EMPTY);
    }

    public BlockPos worldPos(BlockPos anchor) {
        return anchor.offset(offset);
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        return Writer.of()
            .blockPos(TAG_OFFSET, offset)
            .blob(TAG_STATE, NbtUtils.writeBlockState(state))
            .blob(TAG_DATA, data.save())
            .tag();
    }

    static Optional<BlockState> readImported(HolderGetter<Block> blocks, CompoundTag state) {
        ResourceLocation name = ResourceLocation.tryParse(state.getString("Name"));
        if (name == null || blocks.get(ResourceKey.create(Registries.BLOCK, name)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(NbtUtils.readBlockState(blocks, state));
    }

    static Optional<BlockState> read(HolderGetter<Block> blocks, CompoundTag state) {
        if (!state.contains("Name", net.minecraft.nbt.Tag.TAG_STRING)) {
            return Optional.empty();
        }
        ResourceLocation name = ResourceLocation.tryParse(state.getString("Name"));
        if (name == null || blocks.get(ResourceKey.create(Registries.BLOCK, name)).isEmpty()) {
            return Optional.empty();
        }
        BlockState base = blocks.get(ResourceKey.create(Registries.BLOCK, name)).orElseThrow().value()
            .defaultBlockState();
        for (var property : base.getProperties()) {
            if (!state.contains("Properties", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                return Optional.empty();
            }
            CompoundTag properties = state.getCompound("Properties");
            if (!properties.contains(property.getName(), net.minecraft.nbt.Tag.TAG_STRING)
                    || property.getValue(properties.getString(property.getName())).isEmpty()) {
                return Optional.empty();
            }
        }
        return Optional.of(NbtUtils.readBlockState(blocks, state));
    }
}
