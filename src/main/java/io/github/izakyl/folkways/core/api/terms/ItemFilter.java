package io.github.izakyl.folkways.core.api.terms;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record ItemFilter(boolean tag, ResourceLocation id) {
    public ItemFilter {
        Objects.requireNonNull(id, "id");
    }

    public static ItemFilter item(ResourceLocation id) {
        return new ItemFilter(false, id);
    }

    public static ItemFilter tag(ResourceLocation id) {
        return new ItemFilter(true, id);
    }

    public String describe() {
        return tag ? "#" + id : id.toString();
    }
}
