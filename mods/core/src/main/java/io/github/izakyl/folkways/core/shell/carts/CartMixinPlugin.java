package io.github.izakyl.folkways.core.shell.carts;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;

public final class CartMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected boolean enabled(String mixin) {
        return OptionalIntegrations.CARTS.enabled();
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }
}
