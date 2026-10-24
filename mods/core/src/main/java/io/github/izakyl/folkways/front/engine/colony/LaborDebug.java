package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.engine.labor.LaborSnapshot;
import io.github.izakyl.folkways.core.engine.labor.LaborIncident;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// A session subscription belonging to the reader, not a colony setting shared with its other keepers.
public final class LaborDebug {
    private static final int INTERVAL = 200;
    private static final Map<UUID, Watch> WATCHING = new HashMap<>();

    private static final class Watch {
        final UUID colony;
        long next;
        final IncidentFeed incidents = new IncidentFeed();

        Watch(UUID colony) { this.colony = colony; }
    }

    private LaborDebug() { }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(LaborDebug::tick);
        NeoForge.EVENT_BUS.addListener(LaborDebug::incident);
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedOutEvent.class,
            event -> forget(event.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            WATCHING.clear();
            LaborIncident.subscriptions(Set.of());
        });
    }

    public static boolean enabled(Player player) {
        Watch watch = WATCHING.get(player.getUUID());
        return watch != null && Pages.colonyOf(player).map(colony -> watch.colony.equals(colony.id())).orElse(false);
    }

    public static void set(Player player, boolean on) {
        if (!(player instanceof ServerPlayer reader)) return;
        if (!on) {
            if (WATCHING.remove(reader.getUUID()) != null) {
                subscriptions();
                reader.sendSystemMessage(Component.translatable("folkways.debug.disabled"));
            }
            return;
        }
        Pages.colonyOf(reader).ifPresent(colony -> {
            Watch before = WATCHING.get(reader.getUUID());
            if (before == null || !before.colony.equals(colony.id())) {
                WATCHING.put(reader.getUUID(), new Watch(colony.id()));
                subscriptions();
                reader.sendSystemMessage(Component.translatable("folkways.debug.enabled"));
            }
        });
    }

    private static void forget(UUID player) {
        if (WATCHING.remove(player) != null) subscriptions();
    }

    private static void subscriptions() {
        LaborIncident.subscriptions(WATCHING.values().stream().map(watch -> watch.colony).collect(Collectors.toSet()));
    }

    private static void tick(ServerTickEvent.Post event) {
        if (WATCHING.isEmpty()) return;
        var server = event.getServer();
        long now = server.getTickCount();
        if (WATCHING.entrySet().removeIf(entry -> {
            ServerPlayer reader = server.getPlayerList().getPlayer(entry.getKey());
            Colony colony = ColonyGround.of(server, entry.getValue().colony).orElse(null);
            if (reader == null || colony == null || !Charters.member(server, colony, reader.getUUID())) return true;
            Watch watch = entry.getValue();
            watch.incidents.flush(now, (message, count) -> sendIncident(reader, message, count));
            if (now < watch.next) return false;
            watch.next = now + INTERVAL;
            LaborSnapshot.of(reader.serverLevel(), colony.id()).ifPresentOrElse(snapshot -> report(reader, snapshot),
                () -> reader.sendSystemMessage(Component.translatable("folkways.debug.waiting",
                    reader.serverLevel().dimension().location().toString())));
            return false;
        })) subscriptions();
    }

    private static void incident(LaborIncident event) {
        // Engine reports reach here on the server thread; validate membership at delivery, not just at toggle-on.
        boolean logged = false;
        for (var entry : WATCHING.entrySet()) {
            Watch watch = entry.getValue();
            if (!watch.colony.equals(event.colony())) continue;
            var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            ServerPlayer reader = server.getPlayerList().getPlayer(entry.getKey());
            Colony colony = ColonyGround.of(server, watch.colony).orElse(null);
            if (reader == null || colony == null || !Charters.member(server, colony, reader.getUUID())) continue;
            if (!logged) {
                org.slf4j.LoggerFactory.getLogger("folkways-debug").info("incident colony={} key={} {}",
                    event.colony(), event.key(), event.message().getString());
                logged = true;
            }
            watch.incidents.offer(event.key(), event.message(), server.getTickCount(),
                (message, count) -> sendIncident(reader, message, count));
        }
    }

    private static void sendIncident(ServerPlayer reader, Component message, long count) {
        reader.sendSystemMessage(count == 1 ? message
            : Component.translatable("folkways.debug.incident.repeated", message, count));
    }

    static void report(ServerPlayer reader, LaborSnapshot snapshot) {
        var census = snapshot.census();
        int residents = census.standing().values().stream().mapToInt(Integer::intValue).sum();
        int nodes = census.phases().values().stream().mapToInt(Integer::intValue).sum();
        reader.sendSystemMessage(Component.translatable("folkways.debug.status",
            reader.serverLevel().dimension().location().toString(), residents,
            census.standing().getOrDefault("idle", 0), nodes, census.planned(), snapshot.solvingMillis(),
            snapshot.scheduleMillis(), snapshot.limitedSchedules()));
        reader.sendSystemMessage(Component.translatable("folkways.debug.blocked", summary(census.unassigned()),
            summary(snapshot.shortages()), summary(census.missing())));
        if (!snapshot.examples().isEmpty()) {
            reader.sendSystemMessage(Component.translatable("folkways.debug.examples", String.join("; ", snapshot.examples())));
        }
    }

    private static String summary(Map<String, ? extends Number> counts) {
        return counts.entrySet().stream()
            .sorted((a, b) -> {
                int byCount = Long.compare(b.getValue().longValue(), a.getValue().longValue());
                return byCount == 0 ? a.getKey().compareTo(b.getKey()) : byCount;
            })
            .limit(3).map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining(", ", "{", "}"));
    }
}
