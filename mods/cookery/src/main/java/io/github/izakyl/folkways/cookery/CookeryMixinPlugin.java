package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.base.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CookeryMixinPlugin extends FolkwaysMixinPlugin {
    protected Set<String> createOnly() { return Set.of(); }
    protected Map<String, String> modOnly() {
        return Map.of("TableBlockEntityRenderMixin", "kaleidoscope_cookery",
            "MillstoneBlockEntityMixin", "kaleidoscope_cookery",
            "ShawarmaSpitBlockEntityMixin", "kaleidoscope_cookery",
            "SteamerBlockEntityMixin", "kaleidoscope_cookery",
            "BambooTrayAccess", "kaleidoscope_cookery",
            "BambooTrayBlockEntityMixin", "kaleidoscope_cookery");
    }
    protected List<Degradable> degradable() { return List.of(); }
}
