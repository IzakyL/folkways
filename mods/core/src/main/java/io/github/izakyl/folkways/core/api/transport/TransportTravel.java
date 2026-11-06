package io.github.izakyl.folkways.core.api.transport;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import net.minecraft.resources.ResourceLocation;

// A captured read view. Implementations must never observe the world or request missing routes.
@FunctionalInterface
public interface TransportTravel {

    Result estimate(ResourceLocation routeKind, WorldPos from, WorldPos to, long departure);

    sealed interface Result {
    }

    record Yes(long walkTicks, long rideTicks) implements Result {

        public Yes {
            if (walkTicks < 0 || rideTicks < 0) {
                throw new IllegalArgumentException("negative travel time");
            }
        }

        public long ticks() {
            return Math.addExact(walkTicks, rideTicks);
        }
    }

    record No() implements Result {
    }

    record Later() implements Result {
    }

    Result NO = new No();
    Result LATER = new Later();
    TransportTravel UNKNOWN = (kind, from, to, departure) -> LATER;
}
