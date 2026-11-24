package io.github.izakyl.folkways.core.shell.cbc;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;

public final class CbcMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected boolean enabled(String mixin) {
        return OptionalIntegrations.CBC.enabled();
    }

    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }
}
