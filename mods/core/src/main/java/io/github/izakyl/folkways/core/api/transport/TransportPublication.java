package io.github.izakyl.folkways.core.api.transport;

import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

public record TransportPublication(long version, List<Entry> entries) {

    public record Offering(TransportMode mode, TransportSnapshot snapshot, boolean complete) {
    }

    public record Entry(ResourceLocation owner, ResourceKey<Level> dimension, Offering offering) {
    }

    public TransportPublication in(ResourceKey<Level> dimension) {
        return new TransportPublication(version, entries.stream()
            .filter(entry -> entry.dimension().equals(dimension)).toList());
    }

    public TransportPublication {
        entries = List.copyOf(entries);
    }
}
