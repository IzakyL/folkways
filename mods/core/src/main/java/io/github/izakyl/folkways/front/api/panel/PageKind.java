package io.github.izakyl.folkways.front.api.panel;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public record PageKind(ResourceLocation id, String nameKey, ResourceLocation icon,
                      Optional<ResourceLocation> lesson, Optional<ResourceLocation> toolVocation) {

    public PageKind(ResourceLocation id, String nameKey, ResourceLocation icon, Optional<ResourceLocation> lesson) {
        this(id, nameKey, icon, lesson, Optional.empty());
    }

    public PageKind tools(ResourceLocation vocation) {
        return new PageKind(id, nameKey, icon, lesson, Optional.of(vocation));
    }

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon) {
        return boarded(id, nameKey, icon, Optional.empty());
    }

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon,
            Optional<ResourceLocation> lesson) {
        return new PageKind(id, nameKey, icon, lesson);
    }
}
