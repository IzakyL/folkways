package io.github.izakyl.folkways.epicfight.client;

import io.github.izakyl.folkways.epicfight.FolkwaysEpicFight;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.network.server.SPDatapackSync;

/** Waits for Minecraft's actual client resource reload before Epic Fight pairs animations. */
@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class AnimationPairingReloadGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-epicfight-pairing-guard");
    private static final int LIMIT = 16;
    private static final int EVENT_LIMIT = 24;
    private static final ArrayDeque<Pending> PENDING = new ArrayDeque<>();
    private static final ArrayDeque<Map<String, Object>> EVENTS = new ArrayDeque<>();
    private static final ThreadLocal<Boolean> REPLAYING = ThreadLocal.withInitial(() -> false);
    private static CompletableFuture<Void> reload;
    private static long generation;
    private static long session;
    private static long deferred;
    private static long replayed;
    private static long dropped;

    private record Pending(AnimationManager manager, SPDatapackSync packet, boolean mandatory,
            Connection connection, long session) {
    }

    private AnimationPairingReloadGuard() {
    }

    /** Called at the return of Minecraft's private overload, including queued reload requests. */
    public static void observe(CompletableFuture<Void> actualFuture) {
        if (actualFuture == null) {
            return;
        }
        long observed;
        synchronized (AnimationPairingReloadGuard.class) {
            if (reload == actualFuture) {
                return;
            }
            reload = actualFuture;
            observed = ++generation;
            event("reload-observed", "actual-future", null);
        }
        watch(actualFuture, observed);
    }

    /** Returns true only when the native call must be deferred or safely rejected. */
    public static boolean beforeProcess(AnimationManager manager, SPDatapackSync packet, boolean mandatory) {
        if (REPLAYING.get()) {
            return false;
        }
        Connection connection = currentConnection();
        String rejection = null;
        boolean drainQueued = false;
        synchronized (AnimationPairingReloadGuard.class) {
            if (connection == null || !connection.isConnected()) {
                dropped++;
                event("dropped", "no-connected-client", connection);
                return true;
            }
            dropOtherConnections(connection);
            if (reload == null) {
                return false;
            }
            if (reload.isDone()) {
                if (!reload.isCompletedExceptionally() && !reload.isCancelled() && PENDING.isEmpty()) {
                    return false;
                }
                if (reload.isCompletedExceptionally() || reload.isCancelled()) {
                    discardAll("resource-reload-failed");
                    dropped++;
                    event("dropped", "resource-reload-failed", connection);
                    rejection = "Epic Fight pairing stopped: client resource reload failed";
                } else {
                    drainQueued = true;
                }
            }
            if (rejection == null && PENDING.size() >= LIMIT) {
                dropped += PENDING.size() + 1;
                PENDING.clear();
                event("dropped", "queue-overflow", connection);
                rejection = "Epic Fight pairing stopped: too many packets during client resource reload";
            } else if (rejection == null) {
                PENDING.addLast(new Pending(manager, packet, mandatory, connection, session));
                deferred++;
                event("deferred", drainQueued ? "ordered-after-reload" : mandatory ? "mandatory" : "ordinary", connection);
            }
        }
        if (rejection != null) {
            failConnection(connection, rejection);
        } else if (drainQueued) {
            Minecraft.getInstance().execute(AnimationPairingReloadGuard::drain);
        }
        return true;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut ignored) {
        synchronized (AnimationPairingReloadGuard.class) {
            discardAll("logout");
            session++;
            // Resources belong to the Minecraft process. Keep the same future and its one
            // callback; a later connection may also need to wait for this reload to finish.
            event("logout", "pending-cleared", null);
        }
    }

    /** A bounded, immutable readback for live JavaTask assertions. */
    public static synchronized Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", PENDING.size());
        result.put("deferred", deferred);
        result.put("replayed", replayed);
        result.put("dropped", dropped);
        result.put("generation", generation);
        result.put("session", session);
        result.put("reloading", reload != null && !reload.isDone());
        result.put("reloadFailed", reload != null && (reload.isCompletedExceptionally() || reload.isCancelled()));
        result.put("events", List.copyOf(EVENTS));
        return Collections.unmodifiableMap(result);
    }

    private static void watch(CompletableFuture<Void> future, long observed) {
        // The completion closure holds neither the packet nor the connection.
        future.whenComplete((unused, failure) -> {
            String reason = failure == null ? "success" : failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Minecraft.getInstance().execute(() -> completed(observed, reason));
        });
    }

    private static void completed(long observed, String reason) {
        Connection toDisconnect = null;
        synchronized (AnimationPairingReloadGuard.class) {
            if (observed != generation) {
                return;
            }
            if (!"success".equals(reason)) {
                if (!PENDING.isEmpty()) {
                    toDisconnect = PENDING.peekFirst().connection();
                }
                discardAll("reload-failed: " + reason);
                event("reload-failed", reason, toDisconnect);
            } else {
                event("reload-complete", "success", null);
            }
        }
        if (toDisconnect != null) {
            failConnection(toDisconnect, "Epic Fight pairing stopped: client resource reload failed");
        } else if ("success".equals(reason)) {
            drain();
        }
    }

    private static void drain() {
        while (true) {
            Pending pending;
            synchronized (AnimationPairingReloadGuard.class) {
                if (reload == null || !reload.isDone()) {
                    return;
                }
                if (reload.isCompletedExceptionally() || reload.isCancelled()) {
                    Connection connection = PENDING.isEmpty() ? null : PENDING.peekFirst().connection();
                    discardAll("reload-failed-before-replay");
                    if (connection != null) {
                        failConnection(connection, "Epic Fight pairing stopped: client resource reload failed");
                    }
                    return;
                }
                pending = PENDING.pollFirst();
                if (pending == null) {
                    return;
                }
                if (pending.session() != session || pending.connection() != currentConnection()
                        || !pending.connection().isConnected()) {
                    dropped++;
                    event("dropped", "connection-changed-before-replay", pending.connection());
                    continue;
                }
            }
            try {
                // Re-enter the pinned native implementation. Its HEAD hook sees a completed
                // future and permits Epic Fight to construct its CP packet from fresh maps.
                REPLAYING.set(true);
                pending.manager().processServerPacket(pending.packet(), pending.mandatory());
                synchronized (AnimationPairingReloadGuard.class) {
                    replayed++;
                    event("replayed", pending.mandatory() ? "mandatory" : "ordinary", pending.connection());
                }
            } catch (RuntimeException failure) {
                synchronized (AnimationPairingReloadGuard.class) {
                    dropped++;
                    discardAll("native-replay-failed: " + failure.getClass().getSimpleName());
                    event("replay-failed", failure.getClass().getSimpleName(), pending.connection());
                }
                LOGGER.error("Epic Fight animation pairing replay failed", failure);
                failConnection(pending.connection(), "Epic Fight pairing stopped: native animation replay failed");
                return;
            } finally {
                REPLAYING.remove();
            }
        }
    }

    private static void dropOtherConnections(Connection current) {
        if (PENDING.isEmpty() || PENDING.peekFirst().connection() == current) {
            return;
        }
        discardAll("connection-changed");
    }

    private static void discardAll(String reason) {
        if (PENDING.isEmpty()) {
            return;
        }
        dropped += PENDING.size();
        Connection old = PENDING.peekFirst().connection();
        PENDING.clear();
        event("dropped", reason, old);
    }

    private static void failConnection(Connection connection, String reason) {
        if (connection != null && connection.isConnected()) {
            LOGGER.error("{} (connection {})", reason, identity(connection));
            connection.disconnect(Component.literal(reason));
        }
    }

    private static Connection currentConnection() {
        ClientPacketListener listener = Minecraft.getInstance().getConnection();
        return listener == null ? null : listener.getConnection();
    }

    private static void event(String phase, String reason, Connection connection) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("time", Instant.now().toString());
        entry.put("phase", phase);
        entry.put("reason", reason);
        entry.put("connectionId", identity(connection));
        entry.put("generation", generation);
        entry.put("session", session);
        entry.put("queued", PENDING.size());
        EVENTS.addLast(Collections.unmodifiableMap(entry));
        while (EVENTS.size() > EVENT_LIMIT) {
            EVENTS.removeFirst();
        }
        LOGGER.info("pairing reload guard {} reason={} generation={} session={} queued={} connection={}",
            phase, reason, generation, session, PENDING.size(), identity(connection));
    }

    private static String identity(Connection connection) {
        return connection == null ? "" : Integer.toHexString(System.identityHashCode(connection));
    }
}
