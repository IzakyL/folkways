package io.github.izakyl.folkways.core.api.colony;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class Books {

    public interface Source {

        // The colony a book in the player's hand reaches: the one they act for, while they hold any book.
        Optional<UUID> heldColony(Player player);

        // Whether the player is one of the colony's keepers.
        boolean keeps(ServerPlayer player, UUID colony);

        Optional<Colony> acting(Player player);

        Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony);
    }

    private static final Source NONE = new Source() {

        @Override
        public Optional<UUID> heldColony(Player player) {
            return Optional.empty();
        }

        @Override
        public boolean keeps(ServerPlayer player, UUID colony) {
            return false;
        }

        @Override
        public Optional<Colony> acting(Player player) {
            return Optional.empty();
        }

        @Override
        public Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony) {
            return Optional.empty();
        }
    };

    private static Source held = NONE;

    private Books() {
    }

    public static void install(Source source) {
        held = source;
    }

    public static Optional<UUID> heldColony(Player player) {
        return held.heldColony(player);
    }

    public static boolean keeps(ServerPlayer player, UUID colony) {
        return held.keeps(player, colony);
    }

    public static Optional<Colony> acting(Player player) {
        return held.acting(player);
    }

    public static Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony) {
        return held.ofHeldBook(player, colony);
    }
}
