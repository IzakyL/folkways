package io.github.izakyl.folkways.core.transport.api;

import java.util.List;

// Providers return a complete finite candidate set, or mark discovery incomplete in their publication.
public record TransportQuery(TransportEndpoint from, TransportEndpoint to, BatchRequest batch,
                             List<TransportEndpoint> hubs) {

    public TransportQuery {
        hubs = List.copyOf(hubs);
    }
}
