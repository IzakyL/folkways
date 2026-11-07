package io.github.izakyl.folkways.core.transport.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record TransportQuote(ResourceLocation mode, TransportLink link, BatchRequest batch, long covered,
                             long cost, long handedAt, List<Load> loads, List<Occupancy> occupancy,
                             Map<ResourceLocation, Long> versions, Optional<UUID> carrier) {

    // How much time goods spend waiting or on their way weighs against labour. A line that runs anyway, or a parcel
    // on its way, takes no one's work meanwhile: the goods only arrive later, which counts for a quarter as much.
    public static long delay(long ticks) {
        return Math.max(0, ticks) / 4;
    }

    // A repeated load describes equal trips without allocating one record for each trip.
    public record Load(long count, long repetitions, long readyAt, long handedAt) {

        public Load {
            if (count <= 0 || repetitions <= 0 || handedAt < readyAt) {
                throw new IllegalArgumentException("invalid load");
            }
        }
    }

    public record Occupancy(ResourceLocation resource, ResourceLocation unit, long amount) {
    }

    public TransportQuote {
        if (covered <= 0 || covered > batch.count() || cost < 0 || handedAt < batch.readyAt()) {
            throw new IllegalArgumentException("invalid transport quote");
        }
        loads = List.copyOf(loads);
        occupancy = List.copyOf(occupancy);
        versions = Map.copyOf(versions);
        long left = covered;
        for (Load load : loads) {
            if (load.count() > left / load.repetitions()) {
                throw new IllegalArgumentException("loads exceed coverage");
            }
            left -= load.count() * load.repetitions();
        }
        if (left != 0) {
            throw new IllegalArgumentException("loads must cover the quote");
        }
    }
}
