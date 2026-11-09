package io.github.izakyl.folkways.core.front.api.panel;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public record PageKind(ResourceLocation id, String nameKey, ResourceLocation icon,
                      Optional<ResourceLocation> lesson, Optional<ResourceLocation> toolVocation,
                      boolean navigation) {

    public PageKind(ResourceLocation id, String nameKey, ResourceLocation icon,
            Optional<ResourceLocation> lesson, Optional<ResourceLocation> toolVocation) {
        this(id, nameKey, icon, lesson, toolVocation, false);
    }

    public PageKind(ResourceLocation id, String nameKey, ResourceLocation icon, Optional<ResourceLocation> lesson) {
        this(id, nameKey, icon, lesson, Optional.empty());
    }

    public PageKind tools(ResourceLocation vocation) {
        return new PageKind(id, nameKey, icon, lesson, Optional.of(vocation), navigation);
    }

    public PageKind inNavigation() {
        return new PageKind(id, nameKey, icon, lesson, toolVocation, true);
    }

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon) {
        return boarded(id, nameKey, icon, Optional.empty());
    }

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon,
            Optional<ResourceLocation> lesson) {
        return new PageKind(id, nameKey, icon, lesson);
    }
}
