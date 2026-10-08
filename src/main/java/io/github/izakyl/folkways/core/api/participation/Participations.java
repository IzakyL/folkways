package io.github.izakyl.folkways.core.api.participation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Participations {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-participation");

    private static final Map<ResourceLocation, Map<Stake, Participation>> TABLE =
        new LinkedHashMap<>();

    private Participations() {
    }

    public static void register(ResourceLocation kind, Stake what, Participation how) {
        Participation stated = TABLE.computeIfAbsent(kind, id -> new LinkedHashMap<>()).put(what, how);
        if (stated != null && stated != how) {
            LOGGER.info("{} stands to {} as {}, over the {} written earlier", kind, what, how, stated);
        }
    }

    public static void registerDefault(ResourceLocation kind, Stake what, Participation how) {
        TABLE.computeIfAbsent(kind, id -> new LinkedHashMap<>()).putIfAbsent(what, how);
    }

    public static Participation between(ResourceLocation kind, Stake what) {
        return TABLE.getOrDefault(kind, Map.of()).getOrDefault(what, Participation.NONE);
    }

    public static void verify(Set<ResourceLocation> kinds, Set<Stake> named) {
        Set<ResourceLocation> policed = new LinkedHashSet<>();
        named.forEach(what -> policed.add(what.kind()));
        TABLE.forEach((kind, rows) -> {
            if (!kinds.contains(kind)) {
                return;
            }
            rows.keySet().forEach(what -> {
                if (policed.contains(what.kind()) && !named.contains(what)) {
                    throw new IllegalStateException(kind + " states how it stands to " + what
                        + ", which nothing declared");
                }
            });
        });
    }
}
