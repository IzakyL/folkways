package io.github.izakyl.folkways.core.transport.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public record TransportLink(ResourceLocation id, long version, TransportEndpoint from, TransportEndpoint to,
                            List<ItemSpec> accepts, Capacity capacity, Service service,
                            boolean loading, boolean unloading, boolean keptOnArrival) {

    public record Capacity(ResourceLocation unit, long perLoad, long remaining, long minimum, boolean whole) {

        public Capacity {
            if (perLoad <= 0 || remaining < 0 || minimum <= 0) {
                throw new IllegalArgumentException("invalid transport capacity");
            }
        }
    }

    public record Service(ResourceLocation id, long version, long opensAt, long closesAt) {

        public Service {
            if (closesAt < opensAt) {
                throw new IllegalArgumentException("invalid service window");
            }
        }
    }

    public TransportLink {
        accepts = List.copyOf(accepts);
    }
}
