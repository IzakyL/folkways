package io.github.izakyl.folkways.core.work.api;

import net.minecraft.resources.ResourceLocation;

// Consecutive nodes of the same kind may be gathered up to the body's current limit.
public record WorkBatch(ResourceLocation kind, int limit) {

    public boolean accepts(WorkBatch next) {
        return kind.equals(next.kind());
    }
}
