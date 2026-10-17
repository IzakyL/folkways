package io.github.izakyl.folkways.plugins.dispatch;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What one network has left to give: its stock as read at `readAt`, less every order still to be placed and every
 * take by hand still to leave a container. Orders and takes are counted here alike, as so many of the network's
 * goods, since Create packs an order from whichever of its containers it likes. `collecting` is also the lock both
 * maps are changed under.
 */
record Tally(UUID network, List<PackageNetwork.Supply> supply, long readAt, boolean manned,
             Map<UUID, CollectNode> collecting, Map<UUID, TakeNode> taking) {

    Tally {
        supply = List.copyOf(supply);
    }

    long available(ItemSpec spec) {
        long stock = supply.stream()
            .filter(lot -> Goods.matches(lot.item(), spec))
            .mapToLong(PackageNetwork.Supply::count).sum();
        return Math.max(0, stock - unplaced(spec) - untaken(spec));
    }

    // A placed order has already left the network's stock; only those still to be placed hold any of it back.
    private long unplaced(ItemSpec spec) {
        return collecting.values().stream()
            .filter(node -> !node.placed() && node.network().filter(network::equals).isPresent()
                && Goods.overlap(node.goods(), spec))
            .mapToLong(CollectNode::count).sum();
    }

    // A take is in the stock as read until the stock is read again after it.
    private long untaken(ItemSpec spec) {
        return taking.values().stream()
            .filter(node -> node.network().equals(network) && (node.takenAt() < 0 || node.takenAt() >= readAt)
                && Goods.overlap(node.goods(), spec))
            .mapToLong(TakeNode::count).sum();
    }
}
