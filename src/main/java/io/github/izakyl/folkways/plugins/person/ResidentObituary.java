package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.core.api.colony.Books;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

public final class ResidentObituary {

    private ResidentObituary() {
    }

    public static void announce(ServerLevel level, ResidentEntity resident) {
        if (!level.getGameRules().getBoolean(GameRules.RULE_SHOWDEATHMESSAGES)) {
            return;
        }
        BlockPos where = resident.blockPosition();
        Component notice = Component.translatable("folkways.resident.died",
            resident.getCombatTracker().getDeathMessage(),
            Component.translatable("chat.coordinates", where.getX(), where.getY(), where.getZ()));
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            if (resident.colonyId().filter(id -> Books.carried(player, id)).isPresent()) {
                player.sendSystemMessage(notice);
            }
        }
    }
}
