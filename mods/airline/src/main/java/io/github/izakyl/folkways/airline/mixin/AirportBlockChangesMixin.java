package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.AirportRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class AirportBlockChangesMixin {
    @Unique
    private static final TagKey<Block> FOLKWAYS$ACCESSORIES = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath("folkways_airline", "runway_accessories"));

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void folkways$airportChanged(BlockPos pos, BlockState state, boolean moving,
                                         CallbackInfoReturnable<BlockState> result) {
        BlockState previous = result.getReturnValue();
        // A runway accessory changing only its own state (an SW catapult rail marks itself controlled every tick)
        // leaves the surveyed geometry as it was; counting it would keep the airport surveying forever.
        if (previous != null && !previous.equals(state)
                && !(previous.is(state.getBlock()) && state.is(FOLKWAYS$ACCESSORIES))
                && ((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level) {
            AirportRegistry.changed(level, pos, previous, state);
        }
    }
}
