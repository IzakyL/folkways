package io.github.izakyl.folkways.epicfight;

import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import yesman.epicfight.api.animation.AnimationManager;
import yesman.epicfight.api.animation.AnimationManager.AnimationAccessor;
import yesman.epicfight.network.client.CPPairingAnimationRegistry;

/** Bounded, read-only observations of Epic Fight's real animation pairing. */
public final class AnimationPairingEvidence {
    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-epicfight-pairing");
    private static final Gson GSON = new Gson();
    private static final int LIMIT = 24;
    private static final ArrayDeque<Map<String, Object>> EVENTS = new ArrayDeque<>();
    private static final Field ANIMATIONS = field("animations");
    private static final Field BY_ID = field("animationById");
    private static final Field BY_NAME = field("animationByName");
    private static long sequence;
    private static volatile ReloadWindow reloadWindow = new ReloadWindow(0, "not-observed");

    private record ReloadWindow(long generation, String state) {
    }

    private AnimationPairingEvidence() {
    }

    /** The server's decoded packet, immediately before Epic Fight's own validator runs. */
    public static void serverValidate(AnimationManager manager, CPPairingAnimationRegistry packet,
            Object connection) {
        observe(() -> capture(manager, "server-validate", "server", connection, packet.registryNames(), true, true, reloadWindow));
    }

    /** The client's actual packet, immediately before Epic Fight sends it unchanged. */
    public static void clientSend(AnimationManager manager, CPPairingAnimationRegistry packet,
            Object connection) {
        observe(() -> capture(manager, "client-send", "client", connection, packet.registryNames(), true, false, reloadWindow));
    }

    /** Reload phases deliberately read only scalar map sizes; prepare may run off the game thread. */
    public static void reloadPhase(AnimationManager manager, String phase, String side) {
        observe(() -> {
            ReloadWindow window;
            synchronized (AnimationPairingEvidence.class) {
                long generation = reloadWindow.generation() + ("prepare-head".equals(phase) ? 1 : 0);
                window = new ReloadWindow(generation, phase);
                reloadWindow = window;
            }
            capture(manager, phase, side, null, List.of(), false, false, window);
        });
    }

    /** Immutable evidence in insertion order, suitable for a Blockwright JavaTask result. */
    public static synchronized List<Map<String, Object>> snapshot() {
        return List.copyOf(EVENTS);
    }

    public static synchronized String json() {
        return GSON.toJson(EVENTS);
    }

    /** Only discards observations; it does not touch Epic Fight or any network state. */
    public static synchronized void clear() {
        EVENTS.clear();
    }

    private static void capture(AnimationManager manager, String phase, String side, Object connection,
            List<String> names, boolean full, boolean serverComparison, ReloadWindow window) {
        List<String> errors = new ArrayList<>();
        Map<?, ?> animations = read(ANIMATIONS, manager, "animations", errors);
        Map<?, ?> byId = read(BY_ID, manager, "animationById", errors);
        Map<?, ?> byName = read(BY_NAME, manager, "animationByName", errors);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("time", Instant.now().toString());
        event.put("thread", Thread.currentThread().getName());
        event.put("phase", phase);
        event.put("side", side);
        event.put("reloadGeneration", window.generation());
        event.put("reloadState", window.state());
        event.put("connectionIdentity", connection == null ? "" : Integer.toHexString(System.identityHashCode(connection)));
        event.put("animationsSize", size(animations));
        event.put("animationByIdSize", size(byId));
        event.put("animationByNameSize", size(byName));

        List<String> wireNames = List.of();
        List<String> animationNames = List.of();
        List<String> byIdEntries = List.of();
        List<String> byNameNames = List.of();
        List<Integer> idHoles = List.of();
        List<String> serverMissing = List.of();
        List<String> clientMissing = List.of();
        List<String> localWireOnly = List.of();
        List<String> localAnimationOnly = List.of();
        int nullAnimationValues = -1;
        boolean compared = false;
        if (full) {
            try {
                wireNames = List.copyOf(names);
                if (animations != null) {
                    animationNames = animations.keySet().stream().map(String::valueOf).sorted().toList();
                    nullAnimationValues = (int) animations.values().stream().filter(value -> value == null).count();
                }
                if (byId != null) {
                    List<String> entries = new ArrayList<>();
                    byId.forEach((id, accessor) -> entries.add(id + ":" + accessorName(accessor)));
                    Collections.sort(entries);
                    byIdEntries = List.copyOf(entries);
                    if (animations != null) {
                        List<Integer> holes = new ArrayList<>();
                        for (int id = 1; id <= animations.size(); id++) {
                            if (byId.get(id) == null) holes.add(id);
                        }
                        idHoles = List.copyOf(holes);
                    }
                }
                if (byName != null) {
                    byNameNames = byName.keySet().stream().map(String::valueOf).sorted().toList();
                }
                if (animations != null) {
                    Set<String> actual = new HashSet<>(animationNames);
                    Set<String> wire = new HashSet<>(wireNames);
                    if (serverComparison) {
                        Set<String> absentOnServer = new TreeSet<>(wire);
                        absentOnServer.removeAll(actual);
                        absentOnServer.remove("empty"); // Epic Fight's validator ignores this sentinel.
                        Set<String> absentOnClient = new TreeSet<>(actual);
                        absentOnClient.removeAll(wire);
                        serverMissing = List.copyOf(absentOnServer);
                        clientMissing = List.copyOf(absentOnClient);
                        compared = true;
                    } else {
                        Set<String> wireOnly = new TreeSet<>(wire);
                        wireOnly.removeAll(actual);
                        Set<String> animationOnly = new TreeSet<>(actual);
                        animationOnly.removeAll(wire);
                        localWireOnly = List.copyOf(wireOnly);
                        localAnimationOnly = List.copyOf(animationOnly);
                    }
                }
            } catch (RuntimeException e) {
                errors.add("snapshot: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        event.put("wireNames", wireNames);
        event.put("animationNames", animationNames);
        event.put("byIdEntries", byIdEntries);
        event.put("byNameNames", byNameNames);
        event.put("idHoles", idHoles);
        event.put("nullAnimationValues", nullAnimationValues);
        event.put("comparisonAvailable", compared && errors.isEmpty());
        event.put("serverMissing", serverMissing);
        event.put("clientMissing", clientMissing);
        event.put("localWireOnly", localWireOnly);
        event.put("localAnimationOnly", localAnimationOnly);
        event.put("errors", List.copyOf(errors));

        long number;
        synchronized (AnimationPairingEvidence.class) {
            number = ++sequence;
            event.put("seq", number);
            EVENTS.addLast(Collections.unmodifiableMap(event));
            while (EVENTS.size() > LIMIT) EVENTS.removeFirst();
        }
        LOGGER.info("pairing evidence #{} {} {} animations={} byId={} byName={} wire={} serverMissing={} clientMissing={} errors={}",
            number, side, phase, size(animations), size(byId), size(byName), wireNames.size(),
            serverMissing.size(), clientMissing.size(), errors);
    }

    private static String accessorName(Object accessor) {
        return accessor instanceof AnimationAccessor<?> animation ? animation.registryName().toString()
            : String.valueOf(accessor);
    }

    private static void observe(Runnable observation) {
        try {
            observation.run();
        } catch (RuntimeException | LinkageError failed) {
            failed(failed);
        }
    }

    /** Diagnostics must never prevent the original native packet or reload operation. */
    public static void failed(Throwable failure) {
        try {
            LOGGER.warn("Animation pairing observation unavailable", failure);
        } catch (RuntimeException | LinkageError ignored) {
            // The game operation takes precedence even if its logging backend is unavailable.
        }
    }

    private static int size(Map<?, ?> map) {
        return map == null ? -1 : map.size();
    }

    private static Field field(String name) {
        try {
            Field result = AnimationManager.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Cannot inspect Epic Fight animation field {}", name, e);
            return null;
        }
    }

    private static Map<?, ?> read(Field field, AnimationManager manager, String name, List<String> errors) {
        if (field == null) {
            errors.add(name + ": reflection unavailable");
            return null;
        }
        try {
            return (Map<?, ?>) field.get(manager);
        } catch (IllegalAccessException | RuntimeException e) {
            errors.add(name + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
            return null;
        }
    }
}
