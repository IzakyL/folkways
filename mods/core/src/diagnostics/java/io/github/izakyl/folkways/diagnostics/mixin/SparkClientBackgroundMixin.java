package io.github.izakyl.folkways.diagnostics.mixin;

import me.lucko.spark.common.SparkPlatform;
import me.lucko.spark.common.platform.PlatformInfo;
import me.lucko.spark.common.util.config.Configuration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Spark 1.10.124 ignores even an explicit client opt-in. Let its own manager honour the merged setting,
// retaining Spark's normal sampler, history bounds, thread selection, shutdown and restart lifecycle.
// Compiled only for development versions; the plugin skips it when Spark is not installed.
@Pseudo
@Mixin(targets = "me.lucko.spark.common.sampler.BackgroundSamplerManager", remap = false)
public abstract class SparkClientBackgroundMixin {

    @Shadow
    @Final
    @Mutable
    private boolean enabled;

    @Inject(
        method = "<init>(Lme/lucko/spark/common/SparkPlatform;Lme/lucko/spark/common/util/config/Configuration;)V",
        at = @At("RETURN"), require = 1, remap = false)
    private void folkways$honourClientOptIn(SparkPlatform platform, Configuration configuration, CallbackInfo callback) {
        if (platform.getPlugin().getPlatformInfo().getType() == PlatformInfo.Type.CLIENT) {
            enabled = configuration.getBoolean("backgroundProfiler", false);
        }
    }
}
