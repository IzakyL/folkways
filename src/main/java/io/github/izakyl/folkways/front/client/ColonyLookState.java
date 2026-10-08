package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.front.api.notice.Line;
import io.github.izakyl.folkways.front.engine.net.LookAtSnapshotPacket;
import io.github.izakyl.folkways.front.engine.net.LookLines;
import io.github.izakyl.folkways.front.engine.net.RequestLookAtPacket;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.network.PacketDistributor;

public final class ColonyLookState {
    private static final long REFRESH_MS = 250L;
    private static final long CACHE_TTL_MS = 1000L;

    private static List<Line> lines = List.of();
    private static List<LookLines> residents = List.of();
    private static String currentTargetKey = "";
    private static long lastRequestMs;
    private static long lastReplyMs;

    private ColonyLookState() {
    }

    public static void handleLookAtSnapshot(LookAtSnapshotPacket packet) {
        lines = packet.lines();
        residents = packet.residents();
        lastReplyMs = Util.getMillis();
    }

    public static List<Line> lines() {
        return fresh() ? lines : List.of();
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
            reset();
            return;
        }
        UUID colonyId = ColonyHighlightState.heldColonyId(minecraft.player);
        if (colonyId == null) {
            reset();
            return;
        }

        RequestLookAtPacket request = targetRequest(minecraft, colonyId);
        String key = targetKey(request);
        if (!key.equals(currentTargetKey)) {
            currentTargetKey = key;
            lines = List.of();
        }
        long now = Util.getMillis();
        if (now - lastRequestMs >= REFRESH_MS) {
            lastRequestMs = now;
            PacketDistributor.sendToServer(request);
        }
    }

    private static RequestLookAtPacket targetRequest(Minecraft minecraft, UUID colonyId) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return new RequestLookAtPacket(colonyId, Optional.of(blockHit.getBlockPos().immutable()));
        }
        return new RequestLookAtPacket(colonyId, Optional.empty());
    }

    private static String targetKey(RequestLookAtPacket request) {
        return request.blockPos().map(BlockPos::toString).map(pos -> "b:" + pos).orElse("");
    }

    private static void reset() {
        currentTargetKey = "";
        lines = List.of();
        residents = List.of();
    }
}
