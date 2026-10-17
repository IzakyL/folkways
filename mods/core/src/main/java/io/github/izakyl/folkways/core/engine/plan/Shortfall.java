package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.UUID;

// Goods a node needs that nothing could be grown to bring: `holes` names the open edges, `consumer` the node.
public record Shortfall(ItemSpec what, long missing, UUID consumer, Why why, List<UUID> holes) {

    public Shortfall {
        holes = List.copyOf(holes);
    }
}
