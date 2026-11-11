package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.internal.LaborIncident;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID)
public final class DebugHistory {
    private record Entry(long tick, Component message) {
    }

    private final Map<UUID, Deque<Entry>> entries = new LinkedHashMap<>();
    private final Map<UUID, Balk> lastBalk = new LinkedHashMap<>();

    @SubscribeEvent
    public static void incident(LaborIncident event) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) {
            return;
        }
        var level = server.getLevel(event.dimension());
        if (level != null) {
            event.resident().ifPresent(resident -> Labor.site(event.colony(), event.dimension())
                .ifPresent(labor -> labor.history.add(resident, level.getGameTime(),
                    event.brief().orElseGet(event::message))));
        }
    }

    void observe(Body body) {
        body.balk().ifPresent(balk -> {
            if (!balk.equals(lastBalk.put(body.id(), balk))) {
                add(body.id(), balk.at(),
                    DebugText.tone(Component.translatable(balk.why().translationKey()), DebugText.BAD));
            }
        });
    }

    private void add(UUID resident, long tick, Component message) {
        Deque<Entry> ring = entries.computeIfAbsent(resident, ignored -> new ArrayDeque<>());
        // A refusal is told both as an incident and as the balk it leaves: one event, listed once.
        Entry last = ring.peekLast();
        if (last != null && last.tick() == tick && last.message().equals(message)) {
            return;
        }
        if (ring.size() == 8) {
            ring.removeFirst();
        }
        ring.addLast(new Entry(tick, message.copy()));
    }

    List<Line> lines(UUID resident, long now) {
        return entries.getOrDefault(resident, new ArrayDeque<>()).stream()
            .map(entry -> DebugText.line("event", Math.max(0L, now - entry.tick()) / 20L, entry.message()))
            .toList();
    }

    void gone(UUID resident) {
        entries.remove(resident);
        lastBalk.remove(resident);
    }

    void clear() {
        entries.clear();
        lastBalk.clear();
    }
}
