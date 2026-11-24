package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.AirlineTestArea;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture first writes during test execution, including setup that fails before returning a fixture. */
@Mixin(Level.class)
public abstract class TestTerrainMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
        at = @At("HEAD"))
    private void airline$rememberTerrain(BlockPos pos, BlockState state, int flags, int recursion,
                                        CallbackInfoReturnable<Boolean> callback) {
        if ((Object) this instanceof ServerLevel level) {
            AirlineTestArea.changing(level, pos);
        }
    }
}
