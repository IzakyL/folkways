package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.engine.net.LookAtSnapshotPacket;
import io.github.izakyl.folkways.front.engine.net.LookLines;
import io.github.izakyl.folkways.front.engine.net.RequestLookAtPacket;
import java.util.List;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ColonyLookState {
    private static final long REFRESH_MS = 250L;
    private static final long CACHE_TTL_MS = 1000L;

    private static List<LookLines> residents = List.of();
    private static long lastRequestMs;
    private static long lastReplyMs;

    private ColonyLookState() {
    }

    public static void handleLookAtSnapshot(LookAtSnapshotPacket packet) {
        residents = packet.residents();
        lastReplyMs = Util.getMillis();
    }

    public static List<LookLines> residents() {
        return fresh() ? residents : List.of();
    }

    private static boolean fresh() {
        return Util.getMillis() - lastReplyMs <= CACHE_TTL_MS;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.screen != null || minecraft.getConnection() == null) {
            residents = List.of();
            return;
        }
        UUID colonyId = ColonyHighlightState.heldColonyId(minecraft.player);
        if (colonyId == null) {
            residents = List.of();
            return;
        }
        long now = Util.getMillis();
        if (now - lastRequestMs >= REFRESH_MS) {
            lastRequestMs = now;
            PacketDistributor.sendToServer(new RequestLookAtPacket(colonyId));
        }
    }
}
