package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.network.chat.Component;

/** A transfer at this access point disagreed with its simulation. */
public record PortObservation(WorldPos at, Component reason) {
}
