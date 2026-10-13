package io.github.izakyl.folkways.front.api;

import net.minecraft.resources.ResourceLocation;

public record Delegation(ResourceLocation id, Shape shape, Schema schema, ResourceLocation icon) {

    private static final ResourceLocation PLAIN = ResourceLocation.withDefaultNamespace("paper");

    public Delegation(ResourceLocation id, Shape shape) {
        this(id, shape, Schema.none());
    }

    public Delegation(ResourceLocation id, Shape shape, Schema schema) {
        this(id, shape, schema, PLAIN);
    }

    public Delegation(ResourceLocation id, Shape shape, ResourceLocation icon) {
        this(id, shape, Schema.none(), icon);
    }
}
