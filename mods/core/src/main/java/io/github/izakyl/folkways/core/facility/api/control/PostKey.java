package io.github.izakyl.folkways.core.facility.api.control;

import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Identifies the duty post a control requires or a facility supplies. */
public record PostKey(ResourceLocation source, UUID owner, String name) {
}
