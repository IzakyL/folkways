package io.github.izakyl.folkways.core.shell.mixin;

import io.github.izakyl.folkways.core.api.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CoreMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected Set<String> createOnly() {
        return Set.of();
    }

    @Override
    protected Map<String, String> modOnly() {
        return Map.of("SableAssemblyMixin", "sable");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(
            Degradable.common("LevelMixin", "net.minecraft.world.level.Level",
                "a write into a colony's member block no longer reaches the colony, so goods put into a "
                    + "chest are seen only once something else changes that colony, and a block change no "
                    + "longer dirties the reachability graph, so it is rebuilt only when a body wanders "
                    + "off it or a passage moves"));
    }
}
