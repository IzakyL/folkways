package io.github.izakyl.folkways.create.rail.domain;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Trains pulling into a station, told to whoever listens once Create has let one in and done its own mail there.
public final class Arrivals {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final Map<ResourceLocation, BiConsumer<UUID, String>> HEARD = new ConcurrentHashMap<>();

    private Arrivals() {
    }

    // One listener for each owner: listening again replaces it.
    public static void listen(ResourceLocation owner, BiConsumer<UUID, String> arrived) {
        HEARD.put(owner, arrived);
    }

    public static void arrived(UUID train, String station) {
        HEARD.forEach((owner, heard) -> {
            try {
                heard.accept(train, station);
            } catch (RuntimeException | LinkageError broken) {
                LOGGER.error("{} threw hearing train {} arrive at {}", owner, train, station, broken);
            }
        });
    }
}
