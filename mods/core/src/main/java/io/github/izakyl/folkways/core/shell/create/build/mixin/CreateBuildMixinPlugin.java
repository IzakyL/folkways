package io.github.izakyl.folkways.core.shell.create.build.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import java.util.List;
import java.util.Set;

public final class CreateBuildMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected boolean enabled(String mixin) {
        return OptionalIntegrations.CREATE.enabled();
    }

    @Override
    protected Set<String> createOnly() {
        return Set.of("ToolTypeMixin");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(
            Degradable.client("ToolTypeMixin",
                "com.simibubi.create.content.schematics.client.tools.ToolType",
                "Create's row of schematic tools has no entry handing the schematic to a colony, so "
                    + "nothing can place a build order"));
    }
}
