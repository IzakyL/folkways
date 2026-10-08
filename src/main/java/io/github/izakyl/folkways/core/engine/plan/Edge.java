package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Goods a node needs, with nothing yet feeding them: an edge into `consumer` whose other end is still open. The
 * plan grows it a source, and the source a carry to the consumer. `trail` is what the consumer is itself being made
 * for, so no source is grown out of the goods it is meant to make.
 */
public record Edge(UUID id, UUID consumer, Need need, int rank, Set<ItemSpec> trail) {

    public Edge {
        trail = Set.copyOf(trail);
    }

    static UUID idOf(UUID consumer, int need) {
        return UUID.nameUUIDFromBytes(("supply|" + consumer + "|" + need).getBytes(StandardCharsets.UTF_8));
    }

    // Every need a node declares is one open edge into it, named by the need's place.
    static List<Edge> into(Node consumer, int rank, Set<ItemSpec> trail) {
        List<Need> needs = consumer.spec().needs();
        List<Edge> open = new ArrayList<>(needs.size());
        for (int at = 0; at < needs.size(); at++) {
            if (needs.get(at).count() > 0) {
                open.add(new Edge(idOf(consumer.id(), at), consumer.id(), needs.get(at), rank, trail));
            }
        }
        return open;
    }

    public long count() {
        return need.count();
    }

    public ItemSpec spec() {
        return need.spec();
    }

    public boolean carried() {
        return need.carried();
    }

    Edge covering(long count) {
        return new Edge(id, consumer, new Need(need.spec(), count, need.carried()), rank, trail);
    }

    // What a source grown for this edge may not be made from.
    Set<ItemSpec> upstream() {
        Set<ItemSpec> above = new LinkedHashSet<>(trail);
        above.add(need.spec());
        return above;
    }

    boolean loops() {
        return trail.stream().anyMatch(made -> Goods.overlap(made, need.spec()));
    }
}
