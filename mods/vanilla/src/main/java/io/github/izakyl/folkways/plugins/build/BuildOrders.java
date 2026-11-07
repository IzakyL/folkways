package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;

/** Already confirmed drawings from other colony work, with no reusable blueprint to keep afterwards. */
public final class BuildOrders {

    private BuildOrders() {
    }

    public static boolean fileOnce(Colony colony, ServerLevel level, Blueprint blueprint, WorldPos anchor) {
        return colony.service(BuildContent.ID, BuildPresence.class)
            .map(presence -> presence.fileOnce(level, blueprint, anchor.block(level)).isPresent())
            .orElse(false);
    }
}
