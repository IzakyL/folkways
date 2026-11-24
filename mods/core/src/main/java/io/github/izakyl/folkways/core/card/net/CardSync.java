package io.github.izakyl.folkways.core.card.net;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Operations;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Tells each player the live status of the cards they look at, every {@link #PERIOD} ticks and only when it changed:
 * the cards whose windows they hold open, and whatever else a module says they look at (the facilities selected in the
 * command view). Nobody else hears a facility's readings.
 */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class CardSync {
    public static final int PERIOD = 10;
    private static final int MOST_WATCHED = 16;
    private static final Map<ServerPlayer, Set<UUID>> WATCHED = new WeakHashMap<>();
    private static final Map<ServerPlayer, Map<UUID, byte[]>> SENT = new WeakHashMap<>();
    private static final List<Function<ServerPlayer, List<UUID>>> LOOKING = new CopyOnWriteArrayList<>();

    private CardSync() {
    }

    /** Adds what else a player looks at, such as the facilities selected in the command view. */
    public static void looking(Function<ServerPlayer, List<UUID>> source) {
        LOOKING.add(source);
    }

    static void watch(ServerPlayer player, CardWatch watch) {
        if (CurrentColony.of(player).filter(colony -> colony.id().equals(watch.colony())).isEmpty()) {
            return;
        }
        Set<UUID> watched = WATCHED.computeIfAbsent(player, ignored -> new LinkedHashSet<>());
        if (!watch.open()) {
            watched.remove(watch.facility());
            SENT.getOrDefault(player, Map.of()).remove(watch.facility());
        } else if (watched.size() < MOST_WATCHED) {
            watched.add(watch.facility());
            SENT.getOrDefault(player, new HashMap<>()).remove(watch.facility());
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            due(player).forEach(status -> PacketDistributor.sendToPlayer(player, status));
        }
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHED.remove(event.getEntity());
        SENT.remove(event.getEntity());
    }

    /** What this player is owed now: each card they look at whose status changed since they were last told. */
    public static List<CardStatus> due(ServerPlayer player) {
        Colony colony = CurrentColony.of(player).orElse(null);
        if (colony == null) {
            SENT.remove(player);
            WATCHED.remove(player);
            return List.of();
        }
        Set<UUID> looked = new LinkedHashSet<>(WATCHED.getOrDefault(player, Set.of()));
        LOOKING.forEach(source -> looked.addAll(source.apply(player)));
        Map<UUID, byte[]> sent = SENT.computeIfAbsent(player, ignored -> new HashMap<>());
        sent.keySet().retainAll(looked);
        List<CardStatus> due = new ArrayList<>();
        for (UUID facility : looked) {
            Operations.find(colony, facility).ifPresent(operating -> {
                CardStatus status = CardStatus.of(player.getServer(), colony, operating);
                byte[] signature = signature(player, status);
                if (!Arrays.equals(signature, sent.get(facility))) {
                    sent.put(facility, signature);
                    due.add(status);
                }
            });
        }
        return due;
    }

    // Rows carry item stacks, which have no value equality, so a status is compared as it would travel.
    private static byte[] signature(ServerPlayer player, CardStatus status) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            CardStatus.STREAM_CODEC.encode(buffer, status);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }
}
