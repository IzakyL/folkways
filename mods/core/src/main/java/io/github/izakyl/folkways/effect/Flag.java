package io.github.izakyl.folkways.effect;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

// Something a resident does or may do that is no number: it holds while anything grants it and nothing forbids it.
public record Flag(ResourceLocation id) {

    public Flag {
        Objects.requireNonNull(id, "id");
    }
}
