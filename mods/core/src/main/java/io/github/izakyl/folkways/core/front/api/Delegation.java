package io.github.izakyl.folkways.core.front.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * A kind of holding a colony can be handed: its shape, its settings, its icon and whether it can be drawn now. The
 * category says what part of the colony's life it serves, for grouping it with the book's other drawing tools.
 */
public record Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon,
    Supplier<Optional<Component>> availability, ResourceLocation category) {

    private static final ResourceLocation PLAIN = ResourceLocation.withDefaultNamespace("paper");

    public Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon,
            Supplier<Optional<Component>> availability) {
        this(id, shape, schema, icon, availability, Category.OTHER.id());
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon) {
        this(id, shape, schema, icon, Optional::empty);
    }

    public Delegation(ResourceLocation id, Shape shape) {
        this(id, shape, Schema.none());
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema) {
        this(id, shape, schema, PLAIN);
    }

    public Delegation(ResourceLocation id, Shape shape, ResourceLocation icon) {
        this(id, shape, Schema.none(), icon);
    }

    /** The same kind, grouped under that category. */
    public Delegation in(Category category) {
        return new Delegation(id, shape, schema, icon, availability, category.id());
    }

    /** Rechecked on each side when drawing; existing holdings remain valid. */
    public Optional<Component> disabledReason() {
        return availability.get();
    }
}
