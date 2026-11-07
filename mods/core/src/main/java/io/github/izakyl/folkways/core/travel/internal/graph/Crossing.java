package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.resources.ResourceLocation;

record Crossing(WorldPos boarding, WorldPos landing, Hop hop, ResourceLocation passage) {
}
