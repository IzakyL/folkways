package io.github.izakyl.folkways.core.api.transport;

import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.List;
import java.util.Map;

public record TransportSnapshot(long version, long at, TransportTravel travel, List<Carrier> carriers) {

    // Capacities are captured after fitness, bindings, tools and existing claims have been applied.
    public record Carrier(Resident who, WorldPos at, Map<ItemSpec, Long> perTrip,
                          long reachTicks) {

        public Carrier {
            if (reachTicks < 0) {
                throw new IllegalArgumentException("negative handling time");
            }
            perTrip = Map.copyOf(perTrip);
        }

        public long capacity(ItemSpec goods) {
            return Math.max(0, perTrip.getOrDefault(goods, 0L));
        }
    }

    public TransportSnapshot {
        carriers = List.copyOf(carriers);
    }
}
