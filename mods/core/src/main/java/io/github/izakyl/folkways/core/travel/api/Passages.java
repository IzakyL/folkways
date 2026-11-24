package io.github.izakyl.folkways.core.travel.api;

import io.github.izakyl.folkways.core.base.Ledger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class Passages {

    private static final Ledger<Passage> WAYS = new Ledger<>("a way across", Passage::id);

    private record Source(ServerLevel level, UUID colony, Object owner) {
    }

    private static final Map<Source, List<Hop>> PUBLISHED = new LinkedHashMap<>();

    private Passages() {
    }

    public static Passage register(Passage passage) {
        return WAYS.claim(passage);
    }

    /** Replaces the hops published by one source for this colony and level. Server thread only. */
    public static void publish(ServerLevel level, UUID colony, Object owner, List<Hop> hops) {
        Source source = new Source(level, colony, Objects.requireNonNull(owner));
        if (hops.isEmpty()) {
            PUBLISHED.remove(source);
        } else {
            PUBLISHED.put(source, List.copyOf(hops));
        }
    }

    public static List<Hop> published(ServerLevel level, UUID colony) {
        List<Hop> found = new ArrayList<>();
        PUBLISHED.forEach((source, hops) -> {
            if (source.level() == level && source.colony().equals(colony)) {
                found.addAll(hops);
            }
        });
        return List.copyOf(found);
    }

    public static List<ResourceLocation> ids() {
        return WAYS.ids();
    }

    public static List<Passage> all() {
        return WAYS.all();
    }

    public static Optional<Passage> of(ResourceLocation id) {
        return WAYS.of(id);
    }
}
