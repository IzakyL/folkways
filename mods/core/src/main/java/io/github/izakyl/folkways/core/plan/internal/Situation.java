package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public record Situation(Ways ways, Stock stock, List<Workshop> workshops, Stands stands,
                        Supplier<TransportPublication> transports, LongSupplier transportVersion, long at) {

    public Situation(Ways ways, Stock stock, List<Workshop> workshops, Stands stands,
                     Supplier<TransportPublication> transports, long at) {
        this(ways, stock, workshops, stands, transports, () -> transports.get().version(), at);
    }

    public Situation(Ways ways, Stock stock, List<Workshop> workshops, Stands stands) {
        this(ways, stock, workshops, stands, () -> new TransportPublication(0, List.of()), 0);
    }

    public Situation {
        workshops = List.copyOf(workshops);
    }
}
