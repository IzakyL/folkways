package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.shell.protection.FtbProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;

/** Permission checks for automation, evaluated immediately before changing the world. */
public final class WorkProtection {

    private WorkProtection() {
    }

    public static boolean edit(ServerLevel level, Worker who, BlockPos cell) {
        return allowed(level, who, WorldSpaces.at(level, cell), true);
    }

    public static boolean fluid(ServerLevel level, Worker who, BlockPos cell) {
        return !ModList.get().isLoaded("ftbchunks")
            || FtbProtection.fluid(level, who, WorldSpaces.at(level, cell));
    }

    public static boolean interact(ServerLevel level, Worker who, WorldPos at) {
        return allowed(level, who, at, false);
    }

    private static boolean allowed(ServerLevel level, Worker who, WorldPos at, boolean edit) {
        return !ModList.get().isLoaded("ftbchunks") || FtbProtection.allowed(level, who, at, edit);
    }

    public static boolean work(ServerLevel level, Node node, Worker who) {
        if (!ModList.get().isLoaded("ftbchunks")) {
            return true;
        }
        WorkSite site = node.spec().site();
        if (site instanceof WorkSite.AtEntity entity) {
            return entity.entity().equals(who.body().getUUID())
                || FtbProtection.entity(level, who, entity.entity());
        }
        if (site instanceof WorkSite.AtSeat seat) {
            return FtbProtection.entity(level, who, seat.vehicle());
        }
        return (!node.editsBlocks() || allowed(level, who, site.where(), true))
            && allowed(level, who, site.where(), false);
    }
}
