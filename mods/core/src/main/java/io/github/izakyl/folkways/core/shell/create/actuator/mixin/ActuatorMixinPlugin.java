package io.github.izakyl.folkways.core.shell.create.actuator.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;

public final class ActuatorMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected boolean enabled(String mixin) {
        return OptionalIntegrations.CREATE.enabled();
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }

    @Override
    protected Set<String> createOnly() {
        return Set.of("LinearActuatorRead", "RotationPropagatorAccess");
    }
}
