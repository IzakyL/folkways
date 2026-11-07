package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.work.api.PreemptReason;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

/** Tracks active body control separately from suspended work. */
public final class HoldingRegistry {
    public record Held(UUID node, UUID resident, long generation, ResourceLocation owner) {
    }

    public record SuspendedWork(UUID node, UUID lastResident, long sinceTick, ResourceLocation owner,
            PreemptReason reason) {
    }

    private final Map<UUID, Held> residents = new LinkedHashMap<>();
    private final Map<UUID, Held> nodes = new LinkedHashMap<>();
    private final Map<UUID, SuspendedWork> suspended = new LinkedHashMap<>();
    private long generation;

    public Optional<Held> byResident(UUID resident) {
        return Optional.ofNullable(residents.get(resident));
    }

    public Optional<Held> byNode(UUID node) {
        return Optional.ofNullable(nodes.get(node));
    }

    public boolean holds(UUID resident, UUID node) {
        Held held = residents.get(resident);
        return held != null && held.node().equals(node);
    }

    public Optional<SuspendedWork> suspended(UUID node) {
        return Optional.ofNullable(suspended.get(node));
    }

    public List<SuspendedWork> suspendedWorks() {
        return List.copyOf(suspended.values());
    }

    public boolean hasSuspended() {
        return !suspended.isEmpty();
    }

    public Held acquire(UUID resident, UUID node, ResourceLocation owner) {
        if (residents.containsKey(resident) || nodes.containsKey(node)) {
            throw new IllegalStateException("Resident or node is already held");
        }
        Held held = new Held(node, resident, ++generation, owner);
        residents.put(resident, held);
        nodes.put(node, held);
        return held;
    }

    public void suspend(Held held, long tick, PreemptReason reason) {
        if (!held.equals(residents.get(held.resident()))) {
            throw new IllegalStateException("Holding generation is no longer active");
        }
        if (suspended.containsKey(held.node())) {
            throw new IllegalStateException("Node is already suspended");
        }
        release(held);
        suspended.put(held.node(), new SuspendedWork(held.node(), held.resident(), tick, held.owner(), reason));
    }

    public Held resume(UUID node) {
        SuspendedWork work = suspended.get(node);
        if (work == null) {
            throw new IllegalStateException("Node is not suspended");
        }
        Held held = acquire(work.lastResident(), node, work.owner());
        suspended.remove(node);
        return held;
    }

    public void forgetSuspended(UUID node) {
        suspended.remove(node);
    }

    public void release(Held held) {
        if (residents.remove(held.resident(), held)) {
            nodes.remove(held.node(), held);
        }
    }
}
