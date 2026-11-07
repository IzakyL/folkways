package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.colony.api.Claim;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyView;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class ColonyGround {

    private ColonyGround() {
    }

    public static Optional<Colony> of(MinecraftServer server, UUID colony) {
        return Colonies.of(server, colony);
    }

    public static Optional<Colony> ofBlock(ServerLevel level, BlockPos pos) {
        WorldPos cell = WorldPos.of(level, pos);
        for (Colony colony : Colonies.all(level.getServer())) {
            if (colony.claimAt(cell).isPresent()) {
                return Optional.of(colony);
            }
        }
        return Optional.empty();
    }

    public static Colony found(ServerLevel level) {
        return Colonies.mint(level.getServer());
    }

    public static boolean razed(ServerLevel level, UUID colony) {
        return Colonies.razed(level.getServer(), colony);
    }

    public static int raze(ServerLevel level, Colony colony) {
        int sentOff = colony.residents().size();
        ColonyFront.forget(colony.id());
        colony.raze();
        return sentOff;
    }

    // Every block the colony holds in this level, whoever it is held for.
    public static List<BlockPos> cells(ServerLevel level, Colony colony) {
        for (ColonyView view : colony.views(level.getServer())) {
            if (view.dimension().equals(level.dimension())) {
                List<BlockPos> found = new ArrayList<>();
                for (Claim holding : view.claims()) {
                    holding.what().singleCell().flatMap(cell -> WorldSpaces.storage(level, cell)).ifPresent(found::add);
                }
                return List.copyOf(found);
            }
        }
        return List.of();
    }

    public static Optional<Claim> heldAt(ServerLevel level, Colony colony, BlockPos pos) {
        return colony.claimAt(WorldPos.of(level, pos));
    }

    public static boolean holds(ServerLevel level, Colony colony, BlockPos pos) {
        return heldAt(level, colony, pos).isPresent();
    }

    public static Optional<Claim> hold(ServerLevel level, Colony colony, ResourceLocation owner, BlockPos pos) {
        WorldPos at = WorldPos.of(level, pos);
        boolean point = io.github.izakyl.folkways.front.engine.Enrollments.delegation(owner)
            .filter(delegation -> delegation.shape() instanceof io.github.izakyl.folkways.front.api.Shape.Point).isPresent();
        return colony.hold(owner, point ? new Claimed.Point(at) : new Claimed.Block(at));
    }

    public static void letGo(ServerLevel level, Colony colony, BlockPos pos) {
        heldAt(level, colony, pos).ifPresent(held -> colony.release(held.id(), ClaimRelease.LET_GO));
    }
}
