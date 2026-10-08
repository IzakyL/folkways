package io.github.izakyl.folkways.plugins.build.mixin;

import io.github.izakyl.folkways.core.api.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class BuildMixinPlugin extends FolkwaysMixinPlugin {

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
