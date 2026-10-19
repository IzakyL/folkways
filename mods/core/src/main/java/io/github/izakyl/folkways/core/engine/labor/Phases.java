package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Recourse;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;

final class Phases {
    private static final class Entry {
        final Node node;
        final ServerLevel level;
        NodeState state = NodeState.PENDING;
        RefusalKind failure;
        long waitUntil = Long.MIN_VALUE;
        Entry(Node node, ServerLevel level) { this.node = node; this.level = level; }
    }

    private final Map<UUID, Entry> byNode = new LinkedHashMap<>();

    private final BiConsumer<UUID, Ending> ending;

    // Whether a failure asks the node's owner first. A personal urge's work has its own backoff, so it never waits.
    private final boolean asksOwners;

    Phases() {
        this.ending = (node, how) -> { };
        this.asksOwners = false;
    }

    Phases(BiConsumer<UUID, Ending> ending) {
        this.ending = ending;
        this.asksOwners = true;
    }

    void entered(Node node, ServerLevel level) {
        if (byNode.putIfAbsent(node.id(), new Entry(node, level)) == null) {
            changed(byNode.get(node.id()));
        }
    }

    boolean knows(UUID node) {
        return byNode.containsKey(node);
    }

    NodeState of(UUID node) {
        Entry entry = byNode.get(node);
        return entry == null ? NodeState.PENDING : entry.state;
    }

    Node node(UUID id) { return byNode.get(id).node; }

    Optional<RefusalKind> failure(UUID id) {
        Entry entry = byNode.get(id);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.failure);
    }

    boolean check(UUID id) {
        Entry entry = byNode.get(id);
        if (entry == null || entry.state.terminal() || waiting(entry)) {
            return false;
        }
        try {
            entry.node.refusal(entry.level).ifPresent(why -> failed(id, why));
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw checking failure", id, broken);
            fail(id, LaborRefusal.WORK_BROKE);
        }
        return !of(id).terminal() && !waiting(entry);
    }

    boolean waiting(UUID id) {
        Entry entry = byNode.get(id);
        return entry != null && waiting(entry);
    }

    private static boolean waiting(Entry entry) {
        return entry.level.getGameTime() < entry.waitUntil;
    }

    boolean ready(Node node, ServerLevel level) {
        entered(node, level);
        NodeState now = of(node.id());
        if ((now != NodeState.PENDING && now != NodeState.READY) || !check(node.id())) {
            return false;
        }
        try {
            boolean ready = node.ready(level);
            move(node.id(), ready ? NodeState.READY : NodeState.PENDING);
            return ready;
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw checking readiness", node.id(), broken);
            fail(node.id(), LaborRefusal.WORK_BROKE);
            return false;
        }
    }

    void started(UUID node) {
        if (of(node) != NodeState.READY) {
            throw new IllegalStateException(node + " started from " + of(node));
        }
        move(node, NodeState.WORKING);
    }

    void handedOver(UUID node) {
        if (of(node) != NodeState.WORKING) {
            throw new IllegalStateException(node + " handed over from " + of(node));
        }
        move(node, NodeState.SETTLING);
    }

    boolean settled(Node node, ServerLevel level) {
        if (of(node.id()) != NodeState.SETTLING || !check(node.id())) {
            return false;
        }
        try {
            if (node.settled(level)) {
                finished(node.id());
                return true;
            }
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw checking settlement", node.id(), broken);
            fail(node.id(), LaborRefusal.WORK_BROKE);
        }
        return false;
    }

    void finished(UUID node) {
        if (of(node) != NodeState.SETTLING) {
            throw new IllegalStateException(node + " finished from " + of(node));
        }
        end(node, NodeState.DONE, Ending.DONE);
    }

    void failed(UUID node, RefusalKind why) {
        if (!waits(node, why)) {
            fail(node, why);
        }
    }

    // Asks the node's owner what the failure means; answers whether it waits, and if so holds it off till then.
    // A waiting node keeps its phase: one in hand is let go by its worker, one settling goes on settling after.
    boolean waits(UUID node, RefusalKind why) {
        Entry entry = byNode.get(node);
        if (!asksOwners || entry == null || entry.state.terminal()) {
            return false;
        }
        Recourse answer;
        try {
            answer = entry.node.failing(entry.level, why);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw asked about failing with {}", node, why, broken);
            return false;
        }
        if (!(answer instanceof Recourse.Wait(int ticks))) {
            return false;
        }
        entry.waitUntil = entry.level.getGameTime() + ticks;
        return true;
    }

    // Fails the node without asking its owner.
    void fail(UUID node, RefusalKind why) {
        Entry entry = byNode.get(node);
        if (entry != null && !entry.state.terminal()) {
            entry.failure = why;
            end(node, NodeState.FAILED, new Ending.Failed(node, why));
        }
    }

    void letGo(UUID node) {
        if (byNode.containsKey(node) && !of(node).terminal()) {
            move(node, NodeState.PENDING);
        }
    }

    void cancelled(UUID node) {
        cancelled(node, Ending.REVOKED);
    }

    void cancelled(UUID node, Ending how) {
        end(node, NodeState.CANCELLED, how);
    }

    private void end(UUID node, NodeState state, Ending why) {
        Entry entry = byNode.get(node);
        if (entry == null || entry.state.terminal()) {
            return;
        }
        move(node, state);
        try {
            entry.node.ended(entry.level, why);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw ending as {}", node, why, broken);
        }
        ending.accept(node, why);
    }

    private void move(UUID node, NodeState state) {
        Entry entry = byNode.get(node);
        if (entry == null || entry.state == state || entry.state.terminal()) {
            return;
        }
        entry.state = state;
        changed(entry);
    }

    private void changed(Entry entry) {
        try {
            entry.node.changed(entry.level, entry.state);
        } catch (RuntimeException | LinkageError broken) {
            Labor.LOGGER.error("node {} threw observing {}", entry.node.id(), entry.state, broken);
        }
    }

    Set<UUID> settling() { return in(NodeState.SETTLING); }

    Set<UUID> in(NodeState state) {
        Set<UUID> found = new LinkedHashSet<>();
        byNode.forEach((id, entry) -> { if (entry.state == state) found.add(id); });
        return found;
    }

    void retain(Set<UUID> live) {
        retain(live, node -> Ending.REVOKED);
    }

    void retain(Set<UUID> live, Function<UUID, Ending> how) {
        for (UUID node : Set.copyOf(byNode.keySet())) {
            if (!live.contains(node)) {
                cancelled(node, how.apply(node));
                byNode.remove(node);
            }
        }
    }

    void forget(UUID node) { byNode.remove(node); }
}
