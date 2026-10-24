package io.github.izakyl.folkways.plugins.damagecontrol;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

// The address includes its dimension or moving structure, so overlapping zones share one claim.
final class Incidents {
    private final Map<WorldPos, UUID> claims = new LinkedHashMap<>();

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

    boolean free(WorldPos at, UUID worker) {
        return !claims.containsKey(at) || claims.get(at).equals(worker);
    }

    void release(WorldPos at, UUID worker) {
        claims.remove(at, worker);
    }

    int workers() {
        return claims.size();
    }

    void clear() {
        claims.clear();
    }
}
