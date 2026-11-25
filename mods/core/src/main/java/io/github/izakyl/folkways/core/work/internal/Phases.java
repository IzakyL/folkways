package io.github.izakyl.folkways.core.work.internal;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeState;
import io.github.izakyl.folkways.core.work.api.Readiness;
import io.github.izakyl.folkways.core.work.api.Recourse;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Phases {

    private static final long FIRST_READINESS_WAIT = 20;
    private static final long MAX_READINESS_WAIT = 1200;
    private static final long READINESS_CHANGE = 20;

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");
    private static final class Entry {
        final Node node;
        final ServerLevel level;
        NodeState state = NodeState.PENDING;
        RefusalKind failure;
        Readiness.NotYet pending;
        OptionalLong readyAt = OptionalLong.empty();
        long pendingSince = -1;
        long reportedReadyAt = -1;
        long waitUntil = Long.MIN_VALUE;
        Entry(Node node, ServerLevel level) { this.node = node; this.level = level; }
    }

    private final Map<UUID, Entry> byNode = new LinkedHashMap<>();

    private final BiConsumer<UUID, Ending> ending;

    // Whether a failure asks the node's owner first. A personal urge's work has its own backoff, so it never waits.
    private final boolean asksOwners;

    public Phases() {
        this.ending = (node, how) -> { };
        this.asksOwners = false;
    }

    public Phases(BiConsumer<UUID, Ending> ending) {
        this.ending = ending;
        this.asksOwners = true;
    }

    public void entered(Node node, ServerLevel level) {
        if (byNode.putIfAbsent(node.id(), new Entry(node, level)) == null) {
            changed(byNode.get(node.id()));
        }
    }

    public void restart(UUID id) {
        Entry entry = byNode.get(id);
        entry.state = NodeState.PENDING;
        entry.failure = null;
        entry.pending = null;
        entry.readyAt = OptionalLong.empty();
        entry.pendingSince = -1;
        entry.reportedReadyAt = -1;
        entry.waitUntil = Long.MIN_VALUE;
        changed(entry);
    }

    public Consumer<RefusalKind> ownerFailure(UUID id, Consumer<RefusalKind> report) {
        Entry entry = byNode.get(id);
        return why -> {
            if (entry != null && byNode.get(id) == entry && !entry.state.terminal()) {
                fail(id, why);
                report.accept(why);
            }
        };
    }

    public boolean knows(UUID node) {
        return byNode.containsKey(node);
    }

    public NodeState of(UUID node) {
        Entry entry = byNode.get(node);
        return entry == null ? NodeState.PENDING : entry.state;
    }

    public Node node(UUID id) { return byNode.get(id).node; }

    public Optional<RefusalKind> failure(UUID id) {
        Entry entry = byNode.get(id);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.failure);
    }

    public boolean check(UUID id) {
        Entry entry = byNode.get(id);
        return entry != null && !entry.state.terminal() && !waiting(entry);
    }

    public void observeRefusals(BiConsumer<UUID, RefusalKind> report) {
        for (UUID id : List.copyOf(byNode.keySet())) {
            if (!check(id)) {
                continue;
            }
            Entry entry = byNode.get(id);
            try {
                entry.node.refusal(entry.level).ifPresent(why -> failed(id, why));
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("node {} threw checking failure", id, broken);
                fail(id, LaborRefusal.WORK_BROKE);
            }
            failure(id).ifPresent(why -> report.accept(id, why));
        }
    }

    public boolean waiting(UUID id) {
        Entry entry = byNode.get(id);
        return entry != null && waiting(entry);
    }

    public long waitTicks(UUID id, long now) {
        Entry entry = byNode.get(id);
        return entry == null ? 0L : Math.max(0L, entry.waitUntil - now);
    }

    private static boolean waiting(Entry entry) {
        return entry.level.getGameTime() < entry.waitUntil;
    }

    public boolean ready(Node node, ServerLevel level) {
        entered(node, level);
        Entry entry = byNode.get(node.id());
        entry.pending = null;
        entry.readyAt = OptionalLong.empty();
        NodeState now = entry.state;
        if ((now != NodeState.PENDING && now != NodeState.READY) || !check(node.id())) {
            return false;
        }
        try {
            Readiness readiness = node.readiness(level);
            if (readiness instanceof Readiness.NotYet pending) {
                entry.pending = pending;
                long tick = level.getGameTime();
                if (entry.pendingSince < 0) {
                    entry.pendingSince = tick;
                }
                if (pending.ticks().isPresent()) {
                    entry.readyAt = OptionalLong.of(tick + Math.min(pending.ticks().getAsLong(), Long.MAX_VALUE - tick));
                }
            }
            boolean ready = readiness.isReady();
            if (ready) {
                entry.pendingSince = -1;
            }
            move(node.id(), ready ? NodeState.READY : NodeState.PENDING);
            return ready;
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw checking readiness", node.id(), broken);
            fail(node.id(), LaborRefusal.WORK_BROKE);
            return false;
        }
    }

    public OptionalLong readinessUpdate(UUID id, long tick) {
        Entry entry = byNode.get(id);
        if (entry == null || entry.pending == null) {
            return OptionalLong.empty();
        }
        long readyAt = entry.readyAt.orElseGet(() -> backedOff(entry.pendingSince, tick));
        if (entry.reportedReadyAt >= 0 && Math.abs(readyAt - entry.reportedReadyAt) < READINESS_CHANGE) {
            return OptionalLong.empty();
        }
        entry.reportedReadyAt = readyAt;
        return OptionalLong.of(readyAt);
    }

    // Keep the guessed deadline stable between observations so repeated route scans do not move it every tick.
    private static long backedOff(long since, long tick) {
        long elapsed = tick - since;
        long delay = FIRST_READINESS_WAIT;
        while (elapsed >= delay && delay < MAX_READINESS_WAIT) {
            elapsed -= delay;
            delay = Math.min(MAX_READINESS_WAIT, 2 * delay);
        }
        return tick + delay - elapsed % delay;
    }

    public boolean clearReadinessReport(UUID id) {
        Entry entry = byNode.get(id);
        if (entry == null || entry.reportedReadyAt < 0) {
            return false;
        }
        entry.reportedReadyAt = -1;
        return true;
    }

    public void started(UUID node) {
        if (of(node) != NodeState.READY) {
            throw new IllegalStateException(node + " started from " + of(node));
        }
        move(node, NodeState.WORKING);
    }

    public void handedOver(UUID node) {
        if (of(node) != NodeState.WORKING) {
            throw new IllegalStateException(node + " handed over from " + of(node));
        }
        move(node, NodeState.SETTLING);
    }

    public boolean settled(Node node, ServerLevel level) {
        if (of(node.id()) != NodeState.SETTLING || !check(node.id())) {
            return false;
        }
        try {
            if (node.settled(level)) {
                finished(node.id());
                return true;
            }
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw checking settlement", node.id(), broken);
            fail(node.id(), LaborRefusal.WORK_BROKE);
        }
        return false;
    }

    public void finished(UUID node) {
        if (of(node) != NodeState.SETTLING) {
            throw new IllegalStateException(node + " finished from " + of(node));
        }
        end(node, NodeState.DONE, Ending.DONE);
    }

    public void failed(UUID node, RefusalKind why) {
        if (!waits(node, why)) {
            fail(node, why);
        }
    }

    // Asks the node's owner what the failure means; answers whether it waits, and if so holds it off till then.
    // A waiting node keeps its phase: one in hand is let go by its worker, one settling goes on settling after.
    public boolean waits(UUID node, RefusalKind why) {
        Entry entry = byNode.get(node);
        if (!asksOwners || entry == null || entry.state.terminal()) {
            return false;
        }
        Recourse answer;
        try {
            answer = entry.node.failing(entry.level, why);
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw asked about failing with {}", node, why, broken);
            return false;
        }
        if (!(answer instanceof Recourse.Wait(int ticks))) {
            return false;
        }
        entry.waitUntil = entry.level.getGameTime() + ticks;
        return true;
    }

    // Fails the node without asking its owner.
    public void fail(UUID node, RefusalKind why) {
        Entry entry = byNode.get(node);
        if (entry != null && !entry.state.terminal()) {
            entry.failure = why;
            end(node, NodeState.FAILED, new Ending.Failed(node, why));
        }
    }

    public void letGo(UUID node) {
        if (byNode.containsKey(node) && !of(node).terminal()) {
            move(node, NodeState.PENDING);
        }
    }

    public void cancelled(UUID node) {
        cancelled(node, Ending.REVOKED);
    }

    public void cancelled(UUID node, Ending how) {
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
            LOGGER.error("node {} threw ending as {}", node, why, broken);
        }
        ending.accept(node, why);
    }

    private void move(UUID node, NodeState state) {
        Entry entry = byNode.get(node);
        if (entry == null || entry.state == state || entry.state.terminal()) {
            return;
        }
        if (state == NodeState.WORKING || state.terminal()) {
            entry.pending = null;
            entry.readyAt = OptionalLong.empty();
            entry.pendingSince = -1;
            entry.reportedReadyAt = -1;
        }
        entry.state = state;
        changed(entry);
    }

    private void changed(Entry entry) {
        try {
            entry.node.changed(entry.level, entry.state);
        } catch (RuntimeException | LinkageError broken) {
            LOGGER.error("node {} threw observing {}", entry.node.id(), entry.state, broken);
        }
    }

    public Set<UUID> settling() { return in(NodeState.SETTLING); }

    public Set<UUID> in(NodeState state) {
        Set<UUID> found = new LinkedHashSet<>();
        byNode.forEach((id, entry) -> { if (entry.state == state) found.add(id); });
        return found;
    }

    public void retain(Set<UUID> live) {
        retain(live, node -> Ending.REVOKED);
    }

    public void retain(Set<UUID> live, Function<UUID, Ending> how) {
        for (UUID node : Set.copyOf(byNode.keySet())) {
            if (!live.contains(node)) {
                cancelled(node, how.apply(node));
                byNode.remove(node);
            }
        }
    }

    public void forget(UUID node) { byNode.remove(node); }
}
