package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.travel.api.Passages;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

public final class Hops {

    // Which passage runs each service, so a hop is traced to it whatever fare it has since been caught at.
    private static final Map<UUID, Map<ResourceLocation, Passage>> LAID = new ConcurrentHashMap<>();

    private Hops() {
    }

    public static List<Hop> in(Colony colony) {
        List<Hop> found = new ArrayList<>();
        Map<ResourceLocation, Passage> laid = new LinkedHashMap<>();
        for (Passage passage : Passages.all()) {
            for (Hop hop : passage.hopsIn(colony)) {
                found.add(hop);
                laid.put(hop.service(), passage);
            }
        }
        LAID.put(colony.id(), Map.copyOf(laid));
        return List.copyOf(found);
    }

    public static Optional<Passage> laid(Hop hop) {
        for (Map<ResourceLocation, Passage> one : LAID.values()) {
            Passage ran = one.get(hop.service());
            if (ran != null) {
                return Optional.of(ran);
            }
        }
        return Passages.of(hop.service());
    }

    public static void forget(UUID colony) {
        LAID.remove(colony);
    }

    public static void forgetServer() {
        LAID.clear();
    }
}
