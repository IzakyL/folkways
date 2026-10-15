package io.github.izakyl.folkways.front.engine.charter;

import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

// Books from before colonies belonged to players, each naming the colony it was bound to. The first player to carry
// one in, or to use one, takes up the colony it names if no one keeps it yet; either way the book is made a plain one.
public final class LegacyBooks {

    private LegacyBooks() {
    }

    public static void takeUpCarried(ServerPlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            takeUp(player, player.getInventory().getItem(slot));
        }
    }

    public static void takeUp(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof ColonyBookItem) || !stack.has(FolkwaysDataComponents.COLONY_ID.get())) {
            return;
        }
        UUID named = stack.get(FolkwaysDataComponents.COLONY_ID.get());
        stack.remove(FolkwaysDataComponents.COLONY_ID.get());
        MinecraftServer server = player.getServer();
        Optional<Colony> colony = Colonies.razed(server, named) ? Optional.empty() : Colonies.of(server, named);
        if (colony.isEmpty()) {
            player.displayClientMessage(Component.translatable("folkways.colony.razed.book"), true);
            return;
        }
        Charter charter = Charters.of(server, colony.get());
        if (charter.unclaimed()) {
            Charters.keep(colony.get(), charter.foundedBy(player.getUUID()));
            player.displayClientMessage(Component.translatable("folkways.charter.claimed"), false);
        } else if (!charter.member(player.getUUID())) {
            player.displayClientMessage(Component.translatable("folkways.charter.not_yours"), false);
            return;
        }
        CurrentColony.choose(player, colony.get());
    }
}
