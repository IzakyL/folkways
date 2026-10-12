package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Before;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The plan as one reader of it keeps it, built up from what each change shows: the work on the graph, the orders
 * between it both ways, the group of each piece of work bound to others, who each is bound to, and what is held off
 * the graph. Each reader keeps its own; none is shared between threads.
 */
public final class WeaveView {

    private final Map<UUID, Vertex> vertices = new LinkedHashMap<>();
    private final Map<UUID, Set<UUID>> after = new HashMap<>();
    private final Map<UUID, Set<UUID>> before = new HashMap<>();
    private final Map<UUID, Set<UUID>> groups = new HashMap<>();
    private final Map<UUID, UUID> pins = new HashMap<>();
    private Set<UUID> held = Set.of();

    private final Map<UUID, Vertex> readOnly = Collections.unmodifiableMap(vertices);

    public void apply(WeaveDelta delta) {
        for (UUID id : delta.removed()) {
            vertices.remove(id);
            for (UUID to : after.getOrDefault(id, Set.of())) {
                unlink(before, to, id);
            }
            for (UUID from : before.getOrDefault(id, Set.of())) {
                unlink(after, from, id);
            }
            after.remove(id);
            before.remove(id);
            groups.remove(id);
            pins.remove(id);
        }
        vertices.putAll(delta.put());
        for (Before order : delta.unlinked()) {
            unlink(after, order.from(), order.to());
            unlink(before, order.to(), order.from());
        }
        for (Before order : delta.linked()) {
            if (vertices.containsKey(order.from()) && vertices.containsKey(order.to())) {
                after.computeIfAbsent(order.from(), key -> new LinkedHashSet<>()).add(order.to());
                before.computeIfAbsent(order.to(), key -> new LinkedHashSet<>()).add(order.from());
            }
        }
        delta.groups().forEach((id, group) -> {
            if (group.isEmpty() || !vertices.containsKey(id)) {
                groups.remove(id);
            } else {
                groups.put(id, group);
            }
        });
        delta.pins().forEach((id, pin) -> {
            if (pin.isPresent() && vertices.containsKey(id)) {
                pins.put(id, pin.get());
            } else {
                pins.remove(id);
            }
        });
        delta.held().ifPresent(now -> held = now);
    }

    private static void unlink(Map<UUID, Set<UUID>> index, UUID from, UUID to) {
        Set<UUID> out = index.get(from);
        if (out != null && out.remove(to) && out.isEmpty()) {
            index.remove(from);
        }
    }

    public Map<UUID, Vertex> vertices() {
        return readOnly;
    }

    public Vertex vertex(UUID id) {
        return vertices.get(id);
    }

    public boolean has(UUID id) {
        return vertices.containsKey(id);
    }

    public int size() {
        return vertices.size();
    }

    // What must be done before `id`, and what waits on it.
    public Set<UUID> before(UUID id) {
        return before.getOrDefault(id, Set.of());
    }

    public Set<UUID> after(UUID id) {
        return after.getOrDefault(id, Set.of());
    }

    // The work `id` must be done together with, itself among it; just itself when alone.
    public Set<UUID> group(UUID id) {
        Set<UUID> group = groups.get(id);
        return group == null ? Set.of(id) : group;
    }

    public Optional<Set<UUID>> grouped(UUID id) {
        return Optional.ofNullable(groups.get(id));
    }

    public UUID pin(UUID id) {
        return pins.get(id);
    }

    public Map<UUID, UUID> pins() {
        return Collections.unmodifiableMap(pins);
    }

    public Set<UUID> held() {
        return held;
    }

    public void clear() {
        vertices.clear();
        after.clear();
        before.clear();
        groups.clear();
        pins.clear();
        held = Set.of();
    }
}
