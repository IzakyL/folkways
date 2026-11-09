package io.github.izakyl.folkways.vanilla.person.fear;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import net.minecraft.resources.ResourceLocation;

public final class FearContent {

    public static final ResourceLocation FLEEING = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fleeing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "fear");

    public static void declare(Declaring declaring) {
        FearPresence fear = new FearPresence();
        declaring.urges(ID, (colony, who, view) -> fear.urges(who, view));
    }

}
