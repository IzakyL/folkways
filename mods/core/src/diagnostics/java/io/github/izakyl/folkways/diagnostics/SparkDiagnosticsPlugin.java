package io.github.izakyl.folkways.diagnostics;

import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

// Consult only the loading-layer metadata: this runs before game-side ModList exists and must
// neither initialise Spark nor require a Spark class when the optional mod is absent.
public final class SparkDiagnosticsPlugin implements IMixinConfigPlugin {
    // Exact mod metadata version from the validated NeoForge artifact, not its Maven classifier.
    // Other separately installed Spark versions keep their own client lifecycle untouched.
    private static final String VALIDATED_SPARK_VERSION = "1.10.124";

    static boolean supportsSpark(String modId, String modVersion) {
        return "spark".equals(modId) && VALIDATED_SPARK_VERSION.equals(modVersion);
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        var mods = LoadingModList.get();
        if (mods == null) return false;
        var spark = mods.getModFileById("spark");
        return spark != null && spark.getMods().stream().anyMatch(mod ->
            supportsSpark(mod.getModId(), String.valueOf(mod.getVersion())));
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
