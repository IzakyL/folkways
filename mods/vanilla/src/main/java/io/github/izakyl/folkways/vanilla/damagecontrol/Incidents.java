package io.github.izakyl.folkways.vanilla.damagecontrol;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// The address includes its dimension or moving structure, so overlapping zones share one claim.
final class Incidents {
    private final Map<WorldPos, UUID> claims = new LinkedHashMap<>();
    private final Map<WorldPos, UUID> responses = new LinkedHashMap<>();

    boolean claim(WorldPos at, UUID worker, int limit) {
        if (claims.containsKey(at)) {
            return claims.get(at).equals(worker);
        }
        if (claims.size() >= limit || claims.containsValue(worker)) {
            return false;
        }
        claims.put(at, worker);
        return true;
    }

    boolean claim(WorldPos at, UUID worker, UUID response, int limit) {
        UUID current = responses.get(at);
        if (current != null && !current.equals(response) || !claim(at, worker, limit)) {
            return false;
        }
        responses.put(at, response);
        return true;
    }

    boolean free(WorldPos at, UUID worker) {
        return !claims.containsKey(at) || claims.get(at).equals(worker);
    }

    void release(WorldPos at, UUID worker) {
        if (claims.remove(at, worker)) {
            responses.remove(at);
        }
    }

    void release(WorldPos at, UUID worker, UUID response) {
        UUID current = responses.get(at);
        if (current == null || current.equals(response)) {
            release(at, worker);
        }
    }

    boolean available(UUID worker, int limit) {
        return claims.containsValue(worker) || claims.size() < limit;
    }

    Map<WorldPos, UUID> assigned() {
        return Map.copyOf(claims);
    }

    int workers() {
        return claims.size();
    }

    void clear() {
        claims.clear();
        responses.clear();
    }
}
