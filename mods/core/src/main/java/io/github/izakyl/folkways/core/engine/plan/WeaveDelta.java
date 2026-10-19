package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.work.Before;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What changed in the plan since it was last shown, which is all that is ever shown of it: work put on the graph or
 * changed there (`put`), work gone from it (`removed`, taking every order it was part of with it), orders between
 * work on the graph that came (`linked`) or went (`unlinked`), the group of each piece of work whose group changed
 * (empty when it is now alone), the worker each piece of work is now bound to (empty when none), work grown afresh
 * under an id it had before (`fresh`), asked work the plan gave up (`gone`), and - when it changed - the asked work
 * its owner keeps off the plan for now (`held`).
 */
public record WeaveDelta(Map<UUID, Vertex> put, Set<UUID> removed, Set<Before> linked, Set<Before> unlinked,
                         Map<UUID, Set<UUID>> groups, Map<UUID, Optional<UUID>> pins, Set<UUID> fresh,
                         List<Gone> gone, Optional<Set<UUID>> held) {

    public WeaveDelta {
        put = Map.copyOf(put);
        removed = Set.copyOf(removed);
        linked = Set.copyOf(linked);
        unlinked = Set.copyOf(unlinked);
        groups = Map.copyOf(groups);
        pins = Map.copyOf(pins);
        fresh = Set.copyOf(fresh);
        gone = List.copyOf(gone);
        held = held.map(Set::copyOf);
    }

    public static final WeaveDelta NONE = new WeaveDelta(Map.of(), Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
        Set.of(), List.of(), Optional.empty());

    public boolean isEmpty() {
        return put.isEmpty() && removed.isEmpty() && linked.isEmpty() && unlinked.isEmpty() && groups.isEmpty()
            && pins.isEmpty() && fresh.isEmpty() && gone.isEmpty() && held.isEmpty();
    }
}
