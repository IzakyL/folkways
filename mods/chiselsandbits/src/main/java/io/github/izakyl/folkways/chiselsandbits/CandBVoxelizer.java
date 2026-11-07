package io.github.izakyl.folkways.chiselsandbits;

import io.github.izakyl.folkways.core.work.api.StructureCompat;
import io.github.izakyl.folkways.core.work.api.Voxelizer;
import io.github.izakyl.folkways.core.world.api.StructurePayload;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import mod.chiselsandbits.api.block.storage.StateEntryStorage;
import mod.chiselsandbits.api.blockinformation.BlockInformation;
import mod.chiselsandbits.api.chiseling.eligibility.IEligibilityManager;
import mod.chiselsandbits.api.config.IServerConfiguration;
import mod.chiselsandbits.registrars.ModBlocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.state.BlockState;

final class CandBVoxelizer implements Voxelizer {

    @Override
    public StructureCompat.Processing drawing() {
        return CandBStructureCodec.drawing();
    }

    @Override
    public int resolution() {
        return IServerConfiguration.getInstance().getBitSize().get().getBitsPerBlockSide();
    }

    @Override
    public boolean supports(BlockState material) {
        BlockInformation info = new BlockInformation(material, Optional.empty());
        return IEligibilityManager.getInstance().canBeChiseled(info)
            && material.getFluidState().isEmpty()
            && material.getBlock().asItem() instanceof BlockItem;
    }

    @Override
    public BlockState carrier() {
        return ModBlocks.CHISELED_BLOCK.get().defaultBlockState();
    }

    @Override
    public StructurePayload encode(BlockState[] materials) {
        int size = resolution();
        if (materials.length != size * size * size) {
            throw StructureCompat.invalid();
        }
        StateEntryStorage storage = new StateEntryStorage(size);
        Map<BlockState, BlockInformation> palette = new HashMap<>();
        try (var batch = storage.batch()) {
            for (int x = 0; x < size; x++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw StructureCompat.invalid();
                }
                for (int y = 0; y < size; y++) {
                    for (int z = 0; z < size; z++) {
                        BlockState material = materials[(x * size + y) * size + z];
                        if (material != null) {
                            storage.setBlockInformation(x, y, z, palette.computeIfAbsent(material,
                                state -> new BlockInformation(state, Optional.empty())));
                        }
                    }
                }
            }
        }
        try {
            StructurePayload payload = StructureCompat.tagged(carrier(), CandBStructureCodec.encode(storage));
            StructureCompat.inspect(carrier(), payload);
            return payload;
        } catch (StructureCompat.Failure failed) {
            throw failed;
        } catch (RuntimeException failed) {
            throw StructureCompat.invalid();
        }
    }
}
