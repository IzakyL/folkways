package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

record NetworkSnapshot(Map<UUID, Reach> networks, Map<WorldPos, Port> ports) {

    record Reach(List<PackageNetwork.Supply> supply, boolean manned, Optional<PackageNetwork.Flaw> flaw) {

        Reach {
            supply = List.copyOf(supply);
        }

        Reach(List<PackageNetwork.Supply> supply, boolean manned) {
            this(supply, manned, Optional.empty());
        }
    }

    // address is what the port's owner called it; route is what an order is sent to so it lands there.
    record Port(String address, String route, boolean open, List<PackageNetwork.Parcel> parcels,
                Optional<UUID> from, int eta) {

        Port {
            parcels = List.copyOf(parcels);
        }

        Port(String address, boolean open, List<PackageNetwork.Parcel> parcels, Optional<UUID> from, int eta) {
            this(address, address, open, parcels, from, eta);
        }

        boolean addressable() {
            String named = route.trim();
            return !named.isEmpty() && !named.equals("*");
        }
    }

    static NetworkSnapshot empty() {
        return new NetworkSnapshot(Map.of(), Map.of());
    }

    NetworkSnapshot {
        networks = Map.copyOf(networks);
        ports = Map.copyOf(ports);
    }

    Optional<Reach> reachOf(Port port) {
        return port.from().map(networks::get);
    }

    Map<UUID, Long> totals() {
        Map<UUID, Long> counted = new LinkedHashMap<>();
        networks.forEach((id, reach) -> {
            long total = 0;
            for (PackageNetwork.Supply lot : reach.supply()) {
                total += lot.count();
            }
            counted.put(id, total);
        });
        return counted;
    }
}
