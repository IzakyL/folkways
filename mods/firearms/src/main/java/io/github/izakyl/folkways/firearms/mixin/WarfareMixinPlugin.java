package io.github.izakyl.folkways.firearms.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
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
        if (LoadingModList.get().getModFileById("superbwarfare") == null) {
            return List.of();
        }
        var mixins = new ArrayList<>(List.of("ArtilleryAimSourceMixin", "VehicleFireMixin", "VehicleQueueMixin",
            "ProjectileReceiptMixin", "MortarFireMixin", "CustomExplosionMixin", "ExplosionStartMixin"));
        if (FMLEnvironment.dist.isClient()) {
            mixins.add("ArtilleryAimMixin");
        }
        return mixins;
    }
}
