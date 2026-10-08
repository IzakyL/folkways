package io.github.izakyl.folkways.core.api.colony;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class Books {

    public interface Source {

        Optional<UUID> boundColony(ItemStack stack);

        Optional<UUID> heldColony(Player player);

        boolean carried(Player player, UUID colony);

        Optional<Colony> acting(Player player);

        Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony);
    }

    private static final Source NONE = new Source() {

        @Override
        public Optional<UUID> boundColony(ItemStack stack) {
            return Optional.empty();
        }

        @Override
        public Optional<UUID> heldColony(Player player) {
            return Optional.empty();
        }

        @Override
        public boolean carried(Player player, UUID colony) {
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

    public static Optional<UUID> boundColony(ItemStack stack) {
        return held.boundColony(stack);
    }

    public static Optional<UUID> heldColony(Player player) {
        return held.heldColony(player);
    }

    public static boolean carried(Player player, UUID colony) {
        return held.carried(player, colony);
    }

    public static Optional<Colony> acting(Player player) {
        return held.acting(player);
    }

    public static Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony) {
        return held.ofHeldBook(player, colony);
    }
}
