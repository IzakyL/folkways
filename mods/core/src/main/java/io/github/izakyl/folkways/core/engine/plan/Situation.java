package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.transport.TransportPublication;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.travel.Ways;
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
