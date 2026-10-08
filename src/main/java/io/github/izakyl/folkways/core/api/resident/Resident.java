package io.github.izakyl.folkways.core.api.resident;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record Resident(UUID id, ResourceLocation kind) {
}
