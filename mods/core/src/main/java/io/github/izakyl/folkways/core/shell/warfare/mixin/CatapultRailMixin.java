package io.github.izakyl.folkways.core.shell.warfare.mixin;

import com.atsuishio.superbwarfare.block.AircraftCatapultBlock;
import com.atsuishio.superbwarfare.entity.misc.CatapultShuttleEntity;
import io.github.izakyl.folkways.core.shell.warfare.FrameCatapultMotion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AircraftCatapultBlock.class)
public abstract class CatapultRailMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void folkways$frameRail(BlockState state, Level level, BlockPos pos, Entity entity,
                                    CallbackInfo callback) {
        if (entity instanceof CatapultShuttleEntity shuttle && FrameCatapultMotion.bindAtRail(shuttle, pos)) {
            // The frame integrator supplies rail acceleration; native contact would add it along a world axis.
            callback.cancel();
        }
    }
}
