package io.github.izakyl.folkways.vanilla.warfare;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.FMLEnvironment;

public final class WarfareResidentMixinPlugin extends FolkwaysMixinPlugin {
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
        return FMLEnvironment.dist.isClient() ? List.of("ArtilleryAimSourceMixin", "ArtilleryAimMixin")
            : List.of("ArtilleryAimSourceMixin");
    }
}
