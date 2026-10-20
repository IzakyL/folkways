package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Hold;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CompostBookings {

    private record Station(long generation, boolean allowed) {
    }

    private final Map<WorldPos, LinkedHashSet<UUID>> turns = new LinkedHashMap<>();
    private final Map<WorldPos, Station> stations = new LinkedHashMap<>();
    private long epoch;
    private long revision;

    public synchronized void admit(WorldPos at) {
        Station before = stations.get(at);
        if (before == null) {
            stations.put(at, new Station(epoch, true));
        } else if (!before.allowed()) {
            stations.put(at, new Station(++revision, true));
        }
    }

    public synchronized void withdraw(WorldPos at) {
        stations.put(at, new Station(++revision, false));
        turns.remove(at);
    }

    public synchronized boolean free(WorldPos at) {
        return station(at).allowed() && !turns.containsKey(at);
    }

    public synchronized boolean valid(WorldPos at, UUID turn) {
        Set<UUID> queue = turns.get(at);
        return station(at).allowed() && queue != null && queue.contains(turn);
    }

    public synchronized boolean cancelled(WorldPos at, UUID turn) {
        return !valid(at, turn);
    }

    public synchronized long generation(WorldPos at) {
        return station(at).generation();
    }

    public synchronized boolean valid(WorldPos at, UUID turn, long generation) {
        return generation == station(at).generation() && valid(at, turn);
    }

    public synchronized boolean cancelled(WorldPos at, UUID turn, long generation) {
        return !valid(at, turn, generation);
    }
    public synchronized boolean first(WorldPos at, UUID lastNode) {
        Set<UUID> queue = turns.get(at);
        return station(at).allowed() && queue != null && !queue.isEmpty()
            && queue.iterator().next().equals(lastNode);
    }

    public synchronized Hold hold(WorldPos at, UUID lastNode) {
        long generation = station(at).generation();
        long created = epoch;
        return new Hold() {
            private boolean held;
            private Set<UUID> after = Set.of();

            private boolean current() {
                Station now = station(at);
                return created == epoch && generation == now.generation() && now.allowed();
            }

            @Override
            public Optional<RefusalKind> take() {
                synchronized (CompostBookings.this) {
                    if (!current()) {
                        return Optional.of(CompostRefusal.STATION_CHANGED);
                    }
                    if (held) {
                        return Optional.empty();
                    }
                    LinkedHashSet<UUID> queue = turns.computeIfAbsent(at, ignored -> new LinkedHashSet<>());
                    if (queue.contains(lastNode)) {
                        return Optional.of(CompostRefusal.STATION_BUSY);
                    }
                    after = Set.copyOf(queue);
                    queue.add(lastNode);
                    held = true;
                    return Optional.empty();
                }
            }

            @Override
            public Set<UUID> after() {
                return after;
            }

            @Override
            public void release(Ending how) {
                synchronized (CompostBookings.this) {
                    if (!held) {
                        return;
                    }
                    held = false;
                    after = Set.of();
                    // A stable recovery id may be booked again after re-marking; its old hold must not release it.
                    if (!current()) {
                        return;
                    }
                    LinkedHashSet<UUID> queue = turns.get(at);
                    if (queue != null) {
                        queue.remove(lastNode);
                        if (queue.isEmpty()) {
                            turns.remove(at);
                        }
                    }
                }
            }
        };
    }

    private Station station(WorldPos at) {
        return stations.getOrDefault(at, new Station(epoch, true));
    }

    public synchronized void clear() {
        epoch = ++revision;
        turns.clear();
        stations.clear();
    }
}
