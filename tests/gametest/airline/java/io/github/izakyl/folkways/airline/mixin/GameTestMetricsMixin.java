package io.github.izakyl.folkways.airline.mixin;

import io.github.izakyl.folkways.airline.GameTestMetrics;
import net.minecraft.gametest.framework.GameTestBatchFactory;
import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameTestBatchFactory.class)
public abstract class GameTestMetricsMixin {
    @Inject(method = "toGameTestInfo", at = @At("RETURN"))
    private static void airline$recordMetrics(CallbackInfoReturnable<GameTestInfo> callback) {
        callback.getReturnValue().addListener(new GameTestMetrics());
    }
}
