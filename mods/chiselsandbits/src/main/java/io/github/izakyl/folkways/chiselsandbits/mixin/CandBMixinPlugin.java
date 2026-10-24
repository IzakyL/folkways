package io.github.izakyl.folkways.chiselsandbits.mixin;

import io.github.izakyl.folkways.base.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class CandBMixinPlugin extends FolkwaysMixinPlugin {
    @Override
    protected Set<String> createOnly() {
        return Set.of("SchematicPrinterMixin");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of();
    }
}
