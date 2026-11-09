package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.notice.Attempt;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** An extra zone settings button, resolved and executed by the server. */
public record ZoneAction(String key, String nameKey, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        Attempt run(ServerLevel level, Colony colony, UUID zone);
    }
}
