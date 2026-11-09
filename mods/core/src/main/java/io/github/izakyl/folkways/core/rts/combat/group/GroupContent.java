package io.github.izakyl.folkways.core.rts.combat.group;

import io.github.izakyl.folkways.core.extension.api.Declaring;
import net.minecraft.resources.ResourceLocation;

public final class GroupContent {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "unit_groups");

    private GroupContent() {
    }

    public static void declare(Declaring declaring) {
        declaring.colony(
            ID,
            scope -> {
                UnitGroups groups = new UnitGroups(scope.colony(), scope.server());
                scope.share(groups);
                scope.tick(groups::tick);
                scope.closed(why -> groups.clear());
            });
    }
}
