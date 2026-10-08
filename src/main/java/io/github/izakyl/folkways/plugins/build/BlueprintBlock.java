package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.persist.Writer;
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

public record BlueprintBlock(BlockPos offset, BlockState state) {

    static final String TAG_OFFSET = "offset";
    static final String TAG_STATE = "state";

    public BlueprintBlock {
        offset = offset.immutable();
    }

    public BlockPos worldPos(BlockPos anchor) {
        return anchor.offset(offset);
    }

    public CompoundTag save(HolderLookup.Provider provider) {
        return Writer.of()
            .blockPos(TAG_OFFSET, offset)
            .blob(TAG_STATE, NbtUtils.writeBlockState(state))
            .tag();
    }

    static Optional<BlockState> read(HolderGetter<Block> blocks, CompoundTag state) {
        ResourceLocation name = ResourceLocation.tryParse(state.getString("Name"));
        if (name == null || blocks.get(ResourceKey.create(Registries.BLOCK, name)).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(NbtUtils.readBlockState(blocks, state));
    }
}
