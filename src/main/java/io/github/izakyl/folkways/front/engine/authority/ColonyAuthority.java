package io.github.izakyl.folkways.front.engine.authority;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.Residents;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

// A player acting for a colony they keep. Whatever names the colony (an open panel, a block it holds, the colony the
// player has chosen), the player must be among its keepers, or there is no authority at all.
public final class ColonyAuthority {

    public interface Panel {

        Optional<UUID> colonyId();
    }

    private final Colony colony;
    private final Optional<AtBlock> at;

    private ColonyAuthority(Colony colony, Optional<AtBlock> at) {
        this.colony = colony;
        this.at = at;
    }

    public static Optional<ColonyAuthority> of(ServerPlayer player, Optional<BlockPos> named) {
        if (named.isEmpty()) {
            return byContext(player, Optional.empty());
        }
        return AtBlock.of(player, named.get()).flatMap(at -> byContext(player, Optional.of(at)));
    }

    private static Optional<ColonyAuthority> byContext(ServerPlayer player, Optional<AtBlock> at) {
        ServerLevel level = player.serverLevel();
        MinecraftServer server = level.getServer();
        Optional<UUID> panel = openPanelColony(player);
        if (panel.isPresent()) {
            return ColonyGround.of(server, panel.get())
                .filter(colony -> kept(player, colony))
                .map(colony -> new ColonyAuthority(colony, at));
        }
        Optional<Colony> byContainer = at.flatMap(block -> ColonyGround.ofBlock(level, block.pos()))
            .filter(colony -> kept(player, colony));
        if (byContainer.isPresent()) {
            return Optional.of(new ColonyAuthority(byContainer.get(), at));
        }
        return CurrentColony.of(player).map(colony -> new ColonyAuthority(colony, at));
    }

    // Through the book in hand, for the colony the player has chosen: what a click out in the world acts with.
    public static Optional<ColonyAuthority> ofHeldBook(ServerPlayer player, UUID colonyId,
            Optional<BlockPos> named) {
        if (!ColonyBookItem.holdsBookFor(player, colonyId)) {
            return Optional.empty();
        }
        Optional<Colony> colony = CurrentColony.of(player);
        if (colony.isEmpty()) {
            return Optional.empty();
        }
        if (named.isEmpty()) {
            return Optional.of(new ColonyAuthority(colony.get(), Optional.empty()));
        }
        return AtBlock.of(player, named.get())
            .map(at -> new ColonyAuthority(colony.get(), Optional.of(at)));
    }

    public static Optional<ColonyAuthority> ofHeldBook(ServerPlayer player, UUID colonyId) {
        return ofHeldBook(player, colonyId, Optional.empty());
    }

    public Colony colony() {
        return colony;
    }

    public Optional<AtBlock> at() {
        return at;
    }

    public Optional<OwnedResident> resident(MinecraftServer server, UUID residentId) {
        return Residents.of(colony, server, residentId).map(OwnedResident::new);
    }

    private static boolean kept(ServerPlayer player, Colony colony) {
        return Charters.member(player.getServer(), colony, player.getUUID());
    }

    private static Optional<UUID> openPanelColony(ServerPlayer player) {
        return player.containerMenu instanceof ModularUIContainerMenu menu
            && menu.uiHolder instanceof Panel panel
            ? panel.colonyId() : Optional.empty();
    }
}
