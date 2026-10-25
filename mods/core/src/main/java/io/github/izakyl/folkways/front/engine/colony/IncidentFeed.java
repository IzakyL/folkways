package io.github.izakyl.folkways.front.engine.colony;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;

// One reader's bounded queue. First events are immediate; repeats keep their count until the next useful message.
final class IncidentFeed {
    private static final int KEYS = 64;
    private static final int BURST = 4;
    private static final int WINDOW = 40;
    private static final int REPEAT = 100;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private long window;
    private int sent;
    private long overflow;

    private static final class Pending {
        Component message;
        long next = Long.MIN_VALUE;
        long count;
    }

    void offer(String key, Component message, long now, BiConsumer<Component, Long> sink) {
        Pending held = pending.get(key);
        if (held == null) {
            if (pending.size() == KEYS) {
                var unused = pending.entrySet().stream().filter(entry -> entry.getValue().count == 0
                    && now >= entry.getValue().next).findFirst();
                if (unused.isEmpty()) { overflow++; return; }
                pending.remove(unused.get().getKey());
            }
            held = new Pending();
            pending.put(key, held);
        }
        held.message = message.copy();
        held.count++;
        // An offer opening a new window must give older queued keys their turn too.
        if (now >= held.next && room(now)) flush(now, sink);
    }

    void flush(long now, BiConsumer<Component, Long> sink) {
        if (overflow > 0 && room(now)) {
            sink.accept(Component.translatable("folkways.debug.incident.overflow", overflow), 1L);
            overflow = 0;
            sent++;
        }
        for (String key : List.copyOf(pending.keySet())) {
            Pending held = pending.get(key);
            if (held.count > 0 && now >= held.next && room(now)) deliver(key, held, now, sink);
        }
    }

    private boolean room(long now) {
        if (now >= window) { window = now + WINDOW; sent = 0; }
        return sent < BURST;
    }

    private void deliver(String key, Pending held, long now, BiConsumer<Component, Long> sink) {
        sink.accept(held.message, held.count);
        held.count = 0;
        held.next = now + REPEAT;
        sent++;
        pending.remove(key);
        pending.put(key, held);
    }
}
