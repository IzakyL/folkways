package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class BookLookup implements Books.Source {

    private BookLookup() {
    }

    public static void install() {
        Books.install(new BookLookup());
    }

    @Override
    public Optional<UUID> boundColony(ItemStack stack) {
        return stack.getItem() instanceof ColonyBookItem
            ? ColonyBookItem.boundColonyId(stack)
            : Optional.empty();
    }

    @Override
    public Optional<UUID> heldColony(Player player) {
        return HeldBook.bound(player).map(HeldBook::colonyId);
    }

    @Override
    public boolean carried(Player player, UUID colony) {
        return ColonyBookItem.carriesBookBoundTo(player, colony);
    }

    @Override
    public Optional<Colony> acting(Player player) {
        if (!(player instanceof ServerPlayer serving)) {
            return Optional.empty();
        }
        return ColonyAuthority.of(serving, Optional.empty()).map(ColonyAuthority::colony);
    }

    @Override
    public Optional<Colony> ofHeldBook(ServerPlayer player, UUID colony) {
        return ColonyAuthority.ofHeldBook(player, colony).map(ColonyAuthority::colony);
    }
}
