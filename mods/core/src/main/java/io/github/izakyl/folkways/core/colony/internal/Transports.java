package io.github.izakyl.folkways.core.colony.internal;

import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

final class Transports {

    private final Map<ResourceKey<Level>, Map<ResourceLocation, List<TransportPublication.Offering>>> published =
        new LinkedHashMap<>();
    private volatile long version;
    private TransportPublication cached;

    // Whether anything changed: publishing what was already published keeps every quote made from it.
    synchronized boolean publish(ResourceLocation owner, ResourceKey<Level> dimension,
                                 List<TransportPublication.Offering> offering) {
        Map<ResourceLocation, List<TransportPublication.Offering>> here =
            published.computeIfAbsent(dimension, key -> new LinkedHashMap<>());
        List<TransportPublication.Offering> before = here.get(owner);
        if (offering.isEmpty() ? before == null : offering.equals(before)) {
            return false;
        }
        if (offering.isEmpty()) {
            here.remove(owner);
        } else {
            here.put(owner, List.copyOf(offering));
        }
        version++;
        return true;
    }

    long version() {
        return version;
    }

    synchronized TransportPublication snapshot() {
        if (cached != null && cached.version() == version) {
            return cached;
        }
        List<TransportPublication.Entry> entries = new ArrayList<>();
        published.forEach((dimension, owners) -> owners.forEach((owner, offerings) -> offerings.forEach(offering ->
            entries.add(new TransportPublication.Entry(owner, dimension, offering)))));
        cached = new TransportPublication(version, entries);
        return cached;
    }

    synchronized void forget(ResourceLocation owner) {
        published.values().forEach(here -> {
            if (here.remove(owner) != null) {
                version++;
            }
        });
    }

    synchronized void clear() {
        published.clear();
        version++;
    }
}
