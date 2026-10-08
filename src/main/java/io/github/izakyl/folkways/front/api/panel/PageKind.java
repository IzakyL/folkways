package io.github.izakyl.folkways.front.api.panel;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public record PageKind(ResourceLocation id, String nameKey, ResourceLocation icon,
                      Optional<ResourceLocation> lesson) {

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon) {
        return boarded(id, nameKey, icon, Optional.empty());
    }

    public static PageKind boarded(ResourceLocation id, String nameKey, ResourceLocation icon,
            Optional<ResourceLocation> lesson) {
        return new PageKind(id, nameKey, icon, lesson);
    }
}
