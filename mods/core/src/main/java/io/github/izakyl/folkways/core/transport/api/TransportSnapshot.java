package io.github.izakyl.folkways.core.transport.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.world.api.WorldPos;
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
