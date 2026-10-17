package io.github.izakyl.folkways.core.api.passage;

import io.github.izakyl.folkways.core.api.work.Stances;
import net.minecraft.resources.ResourceLocation;

public record Hop(ResourceLocation service, Stances.Cells boarding, Stances.Cells landing, Fare fare) {
}
