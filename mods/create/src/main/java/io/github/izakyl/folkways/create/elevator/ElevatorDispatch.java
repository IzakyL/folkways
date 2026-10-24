package io.github.izakyl.folkways.create.elevator;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

// One controller per physical lift, shared even when several colonies can see the same shaft.
public final class ElevatorDispatch {

    private record Owned(UUID colony, ElevatorController controller) {
    }

    private static final Map<MinecraftServer, Map<ElevatorRef, Owned>> SERVERS = new WeakHashMap<>();
    private static final ElevatorNetwork NETWORK = new CreateElevatorNetwork();

    private ElevatorDispatch() {
    }

    public static ElevatorNetwork network() {
        return NETWORK;
    }

    public static boolean claim(MinecraftServer server, UUID colony, ElevatorRef lift) {
        Map<ElevatorRef, Owned> lifts = SERVERS.computeIfAbsent(server, ignored -> new LinkedHashMap<>());
        Owned previous = lifts.get(lift);
        if (previous != null) {
            return previous.colony().equals(colony) && !previous.controller().retired();
        }
        lifts.put(lift, new Owned(colony, new ElevatorController(lift, NETWORK)));
        return true;
    }

    public static Optional<ElevatorController> controller(MinecraftServer server, UUID colony, ElevatorRef lift) {
        Owned owned = SERVERS.getOrDefault(server, Map.of()).get(lift);
        return owned != null && owned.colony().equals(colony)
            ? Optional.of(owned.controller()) : Optional.empty();
    }

    public static boolean heldByAnother(MinecraftServer server, UUID colony, ElevatorRef lift) {
        Owned owned = SERVERS.getOrDefault(server, Map.of()).get(lift);
        return owned != null && !owned.colony().equals(colony);
    }

    public static void release(MinecraftServer server, UUID colony, ElevatorRef lift) {
        Map<ElevatorRef, Owned> lifts = SERVERS.get(server);
        if (lifts == null) {
            return;
        }
        Owned owned = lifts.get(lift);
        if (owned == null || !owned.colony().equals(colony)) {
            return;
        }
        owned.controller().retire();
        if (owned.controller().idle()) {
            lifts.remove(lift);
        }
    }

    public static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        Map<ElevatorRef, Owned> lifts = SERVERS.get(server);
        if (lifts == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Owned owned : java.util.List.copyOf(lifts.values())) {
            owned.controller().tick(server, now);
        }
        // Retired lifts remain here until their passengers have been set down safely.
        lifts.entrySet().removeIf(entry -> entry.getValue().controller().retired()
            && entry.getValue().controller().idle());
    }

    public static void forget(MinecraftServer server) {
        SERVERS.remove(server);
    }
}
