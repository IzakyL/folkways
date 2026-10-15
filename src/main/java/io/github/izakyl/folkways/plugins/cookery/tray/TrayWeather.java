package io.github.izakyl.folkways.plugins.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.cookery.tray.mixin.BambooTrayAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/** Weather uses the projected station; the actual ship's roof and dripstone remain in storage space. */
public final class TrayWeather {
    private TrayWeather() { }
    public static boolean raining(Level level, BlockPos query, BambooTrayBlockEntity tray) {
        if (!(level instanceof ServerLevel server) || TrayBatch.read(tray, server).isEmpty()) { return level.isRainingAt(query); }
        return WorldSpaces.world(level, WorldPos.of(level, query)).map(BlockPos::containing).map(level::isRainingAt).orElse(false);
    }
    public static boolean exposure(Level level, BlockPos at, BambooTrayBlockEntity tray) {
        boolean local = BambooTrayAccess.folkways$exposure(level, at);
        if (!local || !(level instanceof ServerLevel server) || TrayBatch.read(tray, server).isEmpty()) { return local; }
        return WorldSpaces.world(level, WorldPos.of(level, at)).map(BlockPos::containing)
            .map(world -> world.equals(at) || BambooTrayAccess.folkways$exposure(level, world)).orElse(false);
    }
    static boolean allows(ServerLevel level, BlockPos at, BambooTrayRecipe.Subtype subtype) {
        boolean wet = WorldSpaces.world(level, WorldPos.of(level, at.above())).map(BlockPos::containing)
            .map(level::isRainingAt).orElse(false) || BambooTrayAccess.folkways$dripstone(level, at);
        if (subtype == BambooTrayRecipe.Subtype.WETTING) { return wet; }
        return !wet && !level.isRaining() && BambooTrayAccess.folkways$exposure(level, at)
            && WorldSpaces.world(level, WorldPos.of(level, at)).map(BlockPos::containing)
                .map(world -> world.equals(at) || BambooTrayAccess.folkways$exposure(level, world)).orElse(false);
    }
}
