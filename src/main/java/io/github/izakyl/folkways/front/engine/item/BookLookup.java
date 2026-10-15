package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

public final class BookLookup implements Books.Source {

    private BookLookup() {
    }

    public static void install() {
        Books.install(new BookLookup());
    }

    @Override
    public Optional<UUID> heldColony(Player player) {
        return HeldBook.bound(player).map(HeldBook::colonyId);
    }

    @Override
    public boolean keeps(ServerPlayer player, UUID colony) {
        return Colonies.of(player.getServer(), colony)
            .filter(found -> Charters.member(player.getServer(), found, player.getUUID()))
            .isPresent();
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
