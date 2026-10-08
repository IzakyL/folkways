package io.github.izakyl.folkways.front.engine.net;

import io.github.izakyl.folkways.core.api.colony.Colony;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class ColonyOverviews {

    private static final Map<Key, Cached> BY_COLONY = new HashMap<>();

    private ColonyOverviews() {
    }

    static ColonyOverviewPacket of(ServerPlayer player, Colony colony) {
        ServerLevel level = player.serverLevel();
        Key key = new Key(colony.id(), player.getUUID());
        long now = level.getGameTime();
        Cached cached = BY_COLONY.get(key);
        if (cached != null && cached.tick == now) {
            return cached.packet;
        }
        ColonyOverviewPacket built = FolkwaysUiNetworkEvents.buildOverview(level, colony, player.blockPosition());
        BY_COLONY.values().removeIf(old -> old.tick != now);
        BY_COLONY.put(key, new Cached(now, built));
        return built;
    }

    public static void forgetServer() {
        BY_COLONY.clear();
    }

    private record Key(UUID colony, UUID player) {
        private Key {
            Objects.requireNonNull(colony);
            Objects.requireNonNull(player);
        }
    }

    private record Cached(long tick, ColonyOverviewPacket packet) {
    }
}
