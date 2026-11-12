package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.transport.api.BatchRequest;
import io.github.izakyl.folkways.core.transport.api.LayResult;
import io.github.izakyl.folkways.core.transport.api.QuoteResult;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.transport.api.TransportLaying;
import io.github.izakyl.folkways.core.transport.api.TransportLink;
import io.github.izakyl.folkways.core.transport.api.TransportMode;
import io.github.izakyl.folkways.core.transport.api.TransportQuery;
import io.github.izakyl.folkways.core.transport.api.TransportQuote;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.world.api.Stand;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// A captured store, never a live native network. Custody changes at its put and take nodes; the send is instant.
public record StorageTransport(Stash store, Stock stock, Set<Stand> putting, Set<Stand> taking)
        implements TransportMode {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "storage");
    private static final ResourceLocation ITEMS = ResourceLocation.fromNamespaceAndPath("folkways_core", "items");

    interface Laying extends TransportLaying {
        boolean storage(StorageTransport mode, TransportQuote quote);
    }

    public StorageTransport {
        putting = Set.copyOf(putting);
        taking = Set.copyOf(taking);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
        if (!stock.takes(store) || !stock.stores(store) || !stock.admits(store, query.batch().goods())
                || putting.isEmpty() || taking.isEmpty()
                || putting.stream().noneMatch(put -> taking.stream().anyMatch(take -> !put.equals(take)))
                || !store.pos().sameRealm(query.from().at()) || !store.pos().sameRealm(query.to().at())
                || stock.roomAt(store, query.batch().goods()) < query.batch().count()) {
            return List.of();
        }
        return List.of(new TransportLink(ID, snapshot.version(), endpoint("put", putting), endpoint("take", taking),
            List.of(), new TransportLink.Capacity(ITEMS, Long.MAX_VALUE, Long.MAX_VALUE, 1, false),
            new TransportLink.Service(ID, snapshot.version(), Long.MIN_VALUE, Long.MAX_VALUE), true, true, false));
    }

    @Override
    public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
        if (stock.roomAt(store, batch.goods()) < batch.count()) {
            return QuoteResult.NO;
        }
        return new QuoteResult.Available(new TransportQuote(ID, link, batch, batch.count(), 0, batch.readyAt(),
            List.of(new TransportQuote.Load(batch.count(), 1, batch.readyAt(), batch.readyAt())), List.of(),
            Map.of(ID, snapshot.version()), Optional.empty()), true);
    }

    @Override
    public LayResult lay(TransportQuote quote, TransportLaying plan) {
        return plan instanceof Laying laying && laying.storage(this, quote) ? LayResult.LAID : LayResult.UNAVAILABLE;
    }

    private TransportEndpoint endpoint(String operation, Set<Stand> stands) {
        return Routing.endpoint("storage/" + UUID.nameUUIDFromBytes(
            store.toString().getBytes(StandardCharsets.UTF_8)) + "/" + operation, store.pos(), stands,
            TransportEndpoint.Form.PACK);
    }
}
