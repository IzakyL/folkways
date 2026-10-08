package io.github.izakyl.folkways.core.engine.plan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Claims {

    private final Map<Resource, List<Claim>> byResource = new LinkedHashMap<>();
    private final Map<UUID, List<Claim>> byOwner = new LinkedHashMap<>();
    private long revision;

    public void take(Claim claim) {
        if (claim.amount() <= 0) {
            return;
        }
        byResource.computeIfAbsent(claim.on(), key -> new ArrayList<>()).add(claim);
        byOwner.computeIfAbsent(claim.owner(), key -> new ArrayList<>()).add(claim);
        revision++;
    }

    public long taken(Resource on) {
        long held = 0;
        for (Claim claim : byResource.getOrDefault(on, List.of())) {
            held += claim.amount();
        }
        return held;
    }

    public List<Claim> on(Resource on) {
        return List.copyOf(byResource.getOrDefault(on, List.of()));
    }

    public void release(UUID owner) {
        List<Claim> held = byOwner.remove(owner);
        if (held == null) {
            return;
        }
        revision++;
        for (Claim claim : held) {
            List<Claim> onIt = byResource.get(claim.on());
            if (onIt != null && onIt.remove(claim) && onIt.isEmpty()) {
                byResource.remove(claim.on());
            }
        }
    }

    public List<Claim> since(UUID owner, int mark) {
        List<Claim> held = byOwner.getOrDefault(owner, List.of());
        return mark >= held.size() ? List.of() : List.copyOf(held.subList(mark, held.size()));
    }

    public int count(UUID owner) {
        return byOwner.getOrDefault(owner, List.of()).size();
    }

    public void drop(Claim claim) {
        List<Claim> held = byOwner.get(claim.owner());
        if (held == null || !held.remove(claim)) {
            return;
        }
        revision++;
        if (held.isEmpty()) {
            byOwner.remove(claim.owner());
        }
        List<Claim> onIt = byResource.get(claim.on());
        if (onIt != null && onIt.remove(claim) && onIt.isEmpty()) {
            byResource.remove(claim.on());
        }
    }

    public long revision() {
        return revision;
    }

    public int size() {
        int held = 0;
        for (List<Claim> claims : byResource.values()) {
            held += claims.size();
        }
        return held;
    }
}
