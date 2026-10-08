package io.github.izakyl.folkways.plugins.person.mixin;

import io.github.izakyl.folkways.core.api.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class PersonMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(
            Degradable.client("FontManagerMixin", "net.minecraft.client.gui.font.FontManager",
                "a resource reload triggered by downloaded resident models can leave a cached font "
                    + "pointing at closed bitmap providers, crashing a later HUD draw"),
            Degradable.common("PathNavigationMixin",
                "net.minecraft.world.entity.ai.navigation.PathNavigation",
                "resident pathfinding sees only the blocks of the world itself, so a body walks "
                    + "through a standing carriage as if it were not there and never onto one"));
    }
}
