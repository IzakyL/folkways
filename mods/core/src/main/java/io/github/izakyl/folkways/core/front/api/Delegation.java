package io.github.izakyl.folkways.core.front.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public record Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon,
    Supplier<Optional<Component>> availability, List<ZoneAction> actions) {

    private static final ResourceLocation PLAIN = ResourceLocation.withDefaultNamespace("paper");

    public Delegation {
        actions = List.copyOf(actions);
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon,
        Supplier<Optional<Component>> availability) {
        this(id, shape, schema, icon, availability, List.of());
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon,
        List<ZoneAction> actions) {
        this(id, shape, schema, icon, Optional::empty, actions);
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon) {
        this(id, shape, schema, icon, Optional::empty, List.of());
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

    /** Rechecked on each side when drawing; existing holdings remain valid. */
    public Optional<Component> disabledReason() {
        return availability.get();
    }
}
