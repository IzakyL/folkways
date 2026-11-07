package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.transport.api.TransportEndpoint;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

// Goods with a chosen source, waiting for a rule to decide how they reach their destination.
public record TransportIntent(NodeSpec spec, TransportEndpoint from, TransportEndpoint to, ItemSpec goods,
                              long count, long readyAt) implements Intent {

    public static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways", "transport");

    public static TransportIntent of(TransportEndpoint from, TransportEndpoint to, ItemSpec goods,
                                     long count, long readyAt) {
        return new TransportIntent(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(to.at()),
            Stances.at(to.at()), Workload.Once.of(0)).done(), from, to, goods, count, readyAt);
    }
}
