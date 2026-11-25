package io.github.izakyl.folkways.vanilla.groundwork;

import io.github.izakyl.folkways.core.colony.api.Colony;
import net.minecraft.server.MinecraftServer;

public final class GroundworkFixture {

    private GroundworkFixture() {
    }

    public static void refresh(Colony colony, MinecraftServer server) {
        colony.service(GroundworkContent.ID, GroundPresence.class).orElseThrow().refresh(server);
    }
}
