package io.github.izakyl.folkways.core.shell.warfare;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.LoadingModList;

public final class WarfareMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }

    @Override
    public List<String> getMixins() {
        if (!OptionalIntegrations.SUPERB_WARFARE.enabled()) {
            return List.of();
        }
        var mixins = new ArrayList<>(List.of("VehicleFireMixin", "VehicleQueueMixin",
            "VehicleMotionMixin", "VehicleTargetMixin", "CatapultMotionMixin", "CatapultRailMixin", "ProjectileReceiptMixin", "MortarShotMixin", "VehicleDamageMixin"));
        if (FMLEnvironment.dist.isClient()) {
            if (LoadingModList.get().getModFileById("iris") != null
                && LoadingModList.get().getModFileById("simplebedrockmodel") != null) {
                mixins.add("BedrockTriangleWriterMixin");
            }
        }
        return mixins;
    }
}
