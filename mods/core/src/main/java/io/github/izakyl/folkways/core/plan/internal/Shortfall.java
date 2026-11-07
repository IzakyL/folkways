package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.List;
import java.util.UUID;

// Goods a node needs that nothing could be grown to bring: `holes` names the open edges, `consumer` the node.
public record Shortfall(ItemSpec what, long missing, UUID consumer, Why why, List<UUID> holes) {

    public Shortfall {
        holes = List.copyOf(holes);
    }
}
