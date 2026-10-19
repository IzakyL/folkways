package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.resident.Locomotion;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;

public record Roamer(ResourceLocation kind, Locomotion locomotion, Mob body) {
}
