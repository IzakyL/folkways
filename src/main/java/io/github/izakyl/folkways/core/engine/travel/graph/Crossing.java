package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import net.minecraft.resources.ResourceLocation;

record Crossing(WorldPos boarding, WorldPos landing, Hop hop, ResourceLocation passage) {
}
