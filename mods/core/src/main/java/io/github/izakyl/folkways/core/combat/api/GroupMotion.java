package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/** Autonomous runner policy over the shared planner. No navigation or command handles are owned here. */
public final class GroupMotion {
    public enum Phase {
        ASSEMBLING,
        MOVING,
        ENGAGING,
        REFORMING,
        WATCHING,
        WAITING,
        BLOCKED
    }

    private final GroupRoute route;
    private Map<UUID, WorldPos> destinations = Map.of();
    private Phase phase = Phase.ASSEMBLING;
    private boolean reforming;
    private int stable;
    private int waiting;
    private int retries;
    private int travelling;

    public GroupMotion(BiConsumer<Body, WorldPos> validate, List<Body> bodies, WorldPos destination) {
        route = new GroupRoute(validate, bodies, List.of(destination), -1);
        plan(bodies, route.captain());
    }

    public Phase phase() {
        return phase;
    }

    public Map<UUID, WorldPos> destinations() {
        return destinations;
    }

    public WorldPos anchor() {
        return route.captain();
    }

    private void plan(List<Body> bodies, WorldPos anchor) {
        List<WorldPos> slots = route.slots(bodies, anchor, destinations, Set.of(), Set.of());
        Map<UUID, WorldPos> next = new LinkedHashMap<>();
        for (int index = 0; index < bodies.size(); index++) {
            next.put(bodies.get(index).id(), slots.get(index));
        }
        destinations = Map.copyOf(next);
        route.advanced(anchor);
    }

    public void tick(List<Body> bodies, boolean engaging, boolean paused) {
        if (bodies.isEmpty() || paused) {
            phase = Phase.WAITING;
            stable = 0;
            return;
        }
        if (engaging) {
            phase = Phase.ENGAGING;
            reforming = true;
            stable = 0;
            return;
        }
        if (phase == Phase.BLOCKED) {
            if (destinations.keySet().equals(bodies.stream().map(Body::id).collect(Collectors.toSet()))) {
                return;
            }
            retries = 0;
            waiting = 0;
            travelling = 0;
            phase = Phase.ASSEMBLING;
        }
        try {
            if (!destinations.keySet().equals(bodies.stream().map(Body::id).collect(Collectors.toSet()))) {
                plan(bodies, route.captain());
                stable = 0;
            }
            boolean assembled = bodies.stream().allMatch(body -> at(body, destinations.get(body.id()), 1.3));
            if (reforming || phase == Phase.ASSEMBLING || route.atStop()) {
                phase = reforming ? Phase.REFORMING : route.atStop() ? Phase.WATCHING : Phase.ASSEMBLING;
                stable = assembled ? stable + 1 : 0;
                if (!assembled) {
                    await(bodies);
                    return;
                }
                waiting = 0;
                if (stable < 20) {
                    return;
                }
                reforming = false;
                if (route.atStop()) {
                    return;
                }
            }
            phase = Phase.MOVING;
            boolean cohesive = at(bodies.getFirst(), route.captain(), 2)
                && bodies.stream().allMatch(body -> at(body, destinations.get(body.id()), 3));
            if (!cohesive) {
                await(bodies);
                return;
            }
            if (++travelling >= 1200) {
                phase = Phase.BLOCKED;
                return;
            }
            Optional<WorldPos> next = route.advance(bodies);
            if (next.isEmpty()) {
                await(bodies);
                return;
            }
            plan(bodies, next.get());
            waiting = 0;
            stable = 0;
        } catch (IllegalArgumentException unavailable) {
            await(bodies);
        }
    }

    private void await(List<Body> bodies) {
        if (++waiting < 200) {
            return;
        }
        waiting = 0;
        if (++retries > 2) {
            phase = Phase.BLOCKED;
            return;
        }
        try {
            plan(bodies, route.captain());
        } catch (IllegalArgumentException unavailable) {
            phase = Phase.WAITING;
        }
    }

    public boolean arrived() {
        return route.atStop() && stable >= 40 && phase == Phase.WATCHING;
    }

    private static boolean at(Body body, WorldPos destination, double radius) {
        return destination != null && WorldSpaces.world(body.mob().level(), destination)
            .filter(point -> body.mob().position().distanceToSqr(point) <= radius * radius).isPresent();
    }
}
