package io.github.izakyl.folkways.core.api.colony;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

public final class Colonies {

    public interface Source {

        Optional<Colony> of(MinecraftServer server, UUID colony);

        List<Colony> all(MinecraftServer server);

        Colony mint(MinecraftServer server);

        boolean razed(MinecraftServer server, UUID colony);
    }

    private static final Source NONE = new Source() {

        @Override
        public Optional<Colony> of(MinecraftServer server, UUID colony) {
            return Optional.empty();
        }

        @Override
        public List<Colony> all(MinecraftServer server) {
            return List.of();
        }

        @Override
        public Colony mint(MinecraftServer server) {
            throw new IllegalStateException("nothing installed a place for colonies to be kept");
        }

        @Override
        public boolean razed(MinecraftServer server, UUID colony) {
            return false;
        }
    };

    private static Source held = NONE;

    private Colonies() {
    }

    public static void install(Source source) {
        held = source;
    }

    public static Optional<Colony> of(MinecraftServer server, UUID colony) {
        return held.of(server, colony);
    }

    public static List<Colony> all(MinecraftServer server) {
        return held.all(server);
    }

    public static Colony mint(MinecraftServer server) {
        return held.mint(server);
    }

    public static boolean razed(MinecraftServer server, UUID colony) {
        return held.razed(server, colony);
    }
}
