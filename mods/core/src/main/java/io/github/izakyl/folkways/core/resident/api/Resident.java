package io.github.izakyl.folkways.core.resident.api;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record Resident(UUID id, ResourceLocation kind, ResourceLocation routeKind) {

    public Resident(UUID id, ResourceLocation kind) {
        this(id, kind, kind);
    }
}
