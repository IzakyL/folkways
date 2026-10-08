package io.github.izakyl.folkways.front.engine.authority;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.Residents;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

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
        Optional<UUID> panel = openPanelColony(player);
        if (panel.isPresent()) {
            return ColonyGround.of(level.getServer(), panel.get())
                .map(colony -> new ColonyAuthority(colony, at));
        }
        Optional<Colony> byContainer = at.flatMap(block -> ColonyGround.ofBlock(level, block.pos()));
        if (byContainer.isPresent()) {
            return Optional.of(new ColonyAuthority(byContainer.get(), at));
        }
        return heldBookColony(player)
            .flatMap(id -> ColonyGround.of(level.getServer(), id))
            .map(colony -> new ColonyAuthority(colony, at));
    }

    public static Optional<ColonyAuthority> ofHeldBook(ServerPlayer player, UUID colonyId,
            Optional<BlockPos> named) {
        if (!ColonyBookItem.holdsBookBoundTo(player, colonyId)) {
            return Optional.empty();
        }
        Optional<Colony> colony = ColonyGround.of(player.serverLevel().getServer(), colonyId);
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

    private static Optional<UUID> openPanelColony(ServerPlayer player) {
        return player.containerMenu instanceof ModularUIContainerMenu menu
            && menu.uiHolder instanceof Panel panel
            ? panel.colonyId()
            : player.containerMenu instanceof IModularUIHolder holder
                && holder.getModularUI() instanceof Panel panel
                ? panel.colonyId() : Optional.empty();
    }

    private static Optional<UUID> heldBookColony(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            Optional<UUID> bound = ColonyBookItem.boundColonyId(player.getItemInHand(hand));
            if (bound.isPresent()) {
                return bound;
            }
        }
        return Optional.empty();
    }
}
