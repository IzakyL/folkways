package io.github.izakyl.folkways.core.front.engine;

import io.github.izakyl.folkways.core.colony.api.Closing;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyLifecycle;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import java.util.Map;
import net.minecraft.server.MinecraftServer;

public final class FrontLifecycle {

    private FrontLifecycle() {
    }

    public static void install() {
        ColonyLifecycle.listen(new ColonyLifecycle.Listener() {
            @Override
            public Runnable relocating(MinecraftServer server, Colony colony, Map<WorldPos, WorldPos> moved) {
                return () -> ColonyFront.forget(colony.id());
            }

            @Override
            public void closed(Colony colony, Closing why) {
                ColonyFront.forget(colony.id());
            }
        });
    }
}
