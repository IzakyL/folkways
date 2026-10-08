package io.github.izakyl.folkways.front.api;

import net.minecraft.resources.ResourceLocation;

public record Delegation(ResourceLocation id, Shape shape, Schema schema) {

    public Delegation(ResourceLocation id, Shape shape) {
        this(id, shape, Schema.none());
    }
}
