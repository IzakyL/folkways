package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.engine.travel.Urgency;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.resources.ResourceLocation;

final class Asked {

    private static final int MOST = 4096;

    private final Map<Ask.Key, Ask> box = new ConcurrentHashMap<>();

    private final AtomicLong dropped = new AtomicLong();

    void note(ResourceLocation kind, WorldPos from, Collection<WorldPos> goals) {
        note(kind, null, from, goals, Urgency.BACKGROUND);
    }

    void note(ResourceLocation kind, UUID requester, WorldPos from, Collection<WorldPos> goals, Urgency urgency) {
        Optional<Ask> ask = Ask.of(kind, requester, from, goals, urgency);
        if (ask.isEmpty()) {
            return;
        }
        Ask.Key key = ask.get().key();
        if (box.size() >= MOST && !box.containsKey(key)) {
            dropped.incrementAndGet();
            return;
        }
        box.put(key, ask.get());
    }

    List<Ask> drain() {
        List<Ask> taken = new ArrayList<>(box.size());
        for (Iterator<Ask.Key> keys = box.keySet().iterator(); keys.hasNext(); ) {
            Ask ask = box.remove(keys.next());
            if (ask != null) {
                taken.add(ask);
            }
        }
        return taken;
    }

    long dropped() {
        return dropped.get();
    }
}
