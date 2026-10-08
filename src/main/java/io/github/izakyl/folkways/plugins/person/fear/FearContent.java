package io.github.izakyl.folkways.plugins.person.fear;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.resources.ResourceLocation;

public final class FearContent {

    public static final ResourceLocation FLEEING = ResourceLocation.fromNamespaceAndPath("folkways", "fleeing");

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "fear");

    public static void declare(Declaring declaring) {
        FearPresence fear = new FearPresence();
        declaring.urges(ID, (colony, who, view) -> fear.urges(who, view));
    }

}
