package io.github.izakyl.folkways.core.front.api.ui;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

/**
 * A colony-wide editor too large for a card, such as the name pool or the equipment profiles: a button on one of the
 * book's own pages opens it in a window of the book. {@code icon} is an item id; {@code width} and {@code height} are
 * the window's size until the player resizes it.
 */
public record Editor(ResourceLocation id, String nameKey, ResourceLocation icon, float width, float height,
                     Supplier<? extends Draw> draw) {
    public Editor {
        Objects.requireNonNull(id);
        Objects.requireNonNull(nameKey);
        Objects.requireNonNull(icon);
        Objects.requireNonNull(draw);
    }

    public Editor(ResourceLocation id, String nameKey, ResourceLocation icon, Supplier<? extends Draw> draw) {
        this(id, nameKey, icon, Windows.WIDTH, Windows.HEIGHT, draw);
    }
}
