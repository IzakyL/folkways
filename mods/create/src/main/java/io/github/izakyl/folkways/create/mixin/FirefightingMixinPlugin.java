package io.github.izakyl.folkways.create.mixin;

import io.github.izakyl.folkways.core.base.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;
import net.neoforged.fml.loading.LoadingModList;

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
        if (LoadingModList.get().getModFileById("createfirefightingadd") == null) {
            return List.of();
        }
        return List.of("HydrantCabinetMixin");
    }
}
