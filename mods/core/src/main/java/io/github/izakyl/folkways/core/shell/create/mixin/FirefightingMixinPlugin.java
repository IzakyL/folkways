package io.github.izakyl.folkways.core.shell.create.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;

public final class FirefightingMixinPlugin extends FolkwaysMixinPlugin {
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
        if (!OptionalIntegrations.FIREFIGHTING.enabled()) {
            return List.of();
        }
        return List.of("HydrantCabinetMixin");
    }
}
