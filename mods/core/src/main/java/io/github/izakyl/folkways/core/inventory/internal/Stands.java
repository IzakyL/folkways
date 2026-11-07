package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.world.api.Stand;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class Stands {

    public static final Stands NONE = new Stands(Map.of());

    private final Map<Stash, Set<Stand>> aroundStash;
    private final Map<Stand, Set<Stash>> reachedFrom;

    private Stands(Map<Stash, Set<Stand>> aroundStash) {
        Map<Stash, Set<Stand>> around = new LinkedHashMap<>();
        Map<Stand, Set<Stash>> reach = new LinkedHashMap<>();
        aroundStash.forEach((where, cells) -> {
            around.put(where, Set.copyOf(cells));
            for (Stand cell : cells) {
                reach.computeIfAbsent(cell, key -> new LinkedHashSet<>()).add(where);
            }
        });
        Map<Stand, Set<Stash>> sealedReach = new LinkedHashMap<>();
        reach.forEach((cell, found) -> sealedReach.put(cell, Set.copyOf(found)));
        this.aroundStash = Map.copyOf(around);
        this.reachedFrom = Map.copyOf(sealedReach);
    }

    public Set<Stash> reach(Stand at) {
        return reachedFrom.getOrDefault(at, Set.of());
    }

    public Set<Stand> around(Stash where) {
        return aroundStash.getOrDefault(where, Set.of());
    }

    public Set<Stash> reachedBy(Collection<Stand> cells) {
        Set<Stash> found = new LinkedHashSet<>();
        for (Stand cell : cells) {
            found.addAll(reach(cell));
        }
        return found;
    }

    public static Builder building() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<Stash, Set<Stand>> around = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder container(Stash where, Collection<Stand> cells) {
            for (Stand cell : cells) {
                if (!where.pos().sameRealm(cell.cell())) {
                    throw new IllegalArgumentException(
                        "a closure that crosses realms would depend on where the realm is: "
                            + where + " from " + cell);
                }
                around.computeIfAbsent(where, key -> new LinkedHashSet<>()).add(cell);
            }
            return this;
        }

        public Stands done() {
            return around.isEmpty() ? NONE : new Stands(around);
        }
    }
}
