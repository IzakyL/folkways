package io.github.izakyl.folkways.core.travel.api;

import io.github.izakyl.folkways.core.work.api.Stances;
import net.minecraft.resources.ResourceLocation;

public record Hop(ResourceLocation service, Stances.Cells boarding, Stances.Cells landing, Fare fare) {
}
