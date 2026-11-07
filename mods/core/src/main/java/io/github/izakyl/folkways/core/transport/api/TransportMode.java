package io.github.izakyl.folkways.core.transport.api;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

// Discovery and quoting only read the supplied snapshot; reservations belong to lay.
public interface TransportMode {

    ResourceLocation id();

    List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot);

    QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot);

    LayResult lay(TransportQuote quote, TransportLaying plan);
}
