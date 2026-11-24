package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/** Server-wide crafting reservations; the native adapter selects its executors. */
public abstract class NetworkTally<K, E, N> {

    private final List<Ticket> tickets = new ArrayList<>();
    private final CraftRefusals refusals;

    protected NetworkTally(CraftRefusals refusals) {
        this.refusals = refusals;
    }

    protected abstract E select(K network, List<E> candidates);

    protected abstract N nativeExecutor(E executor);

    protected synchronized boolean reserved(Predicate<Ticket> matches) {
        return tickets.stream().filter(ticket -> !ticket.cleaned).anyMatch(matches);
    }

    // Crafting orders this colony holds on the network now, against its share of the network's CPUs.
    public synchronized int crafting(UUID owner, K network) {
        return (int) tickets.stream().filter(ticket -> !ticket.released && !ticket.cleaned
            && ticket.owner.equals(owner) && ticket.network.equals(network)).count();
    }

    public Ticket ticket(UUID owner, K network, ItemSpec want, long count, List<E> candidates) {
        return new Ticket(owner, network, want, count, candidates);
    }

    public synchronized void close(UUID owner) {
        tickets.stream().filter(ticket -> ticket.owner.equals(owner)).forEach(ticket -> ticket.released = true);
        clean();
    }

    protected synchronized void cancelAll() {
        tickets.forEach(ticket -> ticket.released = true);
        clean();
    }

    public synchronized void clean() {
        for (Ticket ticket : tickets) {
            if (ticket.released && !ticket.cleaned) {
                if (ticket.cancel != null) {
                    ticket.cancel.run();
                }
                ticket.cleaned = true;
            }
        }
        tickets.removeIf(ticket -> ticket.cleaned);
    }

    public final class Ticket implements Hold {
        private final UUID owner;
        private final K network;
        private final ItemSpec want;
        private final long count;
        private final List<E> candidates;
        private volatile E executor;
        private volatile Runnable cancel;
        private int estimate;
        private int limit = Integer.MAX_VALUE;
        private Map<String, Long> patterns = Map.of();
        private boolean taken;
        private boolean released;
        private boolean cleaned;

        private Ticket(UUID owner, K network,     ItemSpec want, long count, List<E> candidates) {
            this.owner = owner;
            this.network = network;
            this.want = want;
            this.count = count;
            this.candidates = List.copyOf(candidates);
        }

        public ItemSpec wanted() {
            return want;
        }

        public long count() {
            return count;
        }

        public K network() {
            return network;
        }

        @Override
        public Optional<RefusalKind> take() {
            synchronized (NetworkTally.this) {
                if (released) {
                    return Optional.of(refusals.canceled());
                }
                if (taken) {
                    return Optional.empty();
                }
                if (NetworkTally.this.crafting(owner, network) >= limit) {
                    return Optional.of(NetworkRefusal.LIMIT);
                }
                executor = select(network, candidates);
                if (executor == null) {
                    return Optional.of(refusals.busy());
                }
                tickets.add(this);
                taken = true;
                return Optional.empty();
            }
        }

        public N nativeExecutor() {
            return NetworkTally.this.nativeExecutor(executor);
        }

        public E executor() {
            return executor;
        }

        public void cancelWith(Runnable cancel) {
            this.cancel = cancel;
        }

        public void estimate(int ticks) {
            estimate = ticks;
        }

        public void limit(int count) {
            limit = count;
        }

        public Map<String, Long> patterns() {
            return patterns;
        }

        public void patterns(Map<String, Long> runs) {
            patterns = runs;
        }

        @Override
        public long estimatedWaitTicks() {
            return estimate;
        }

        @Override
        public void release(Ending how) {
            synchronized (NetworkTally.this) {
                released = true;
            }
        }
    }
}
