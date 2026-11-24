package io.github.izakyl.folkways.core.facility.api.control;

import io.github.izakyl.folkways.core.world.api.Stances;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Optional;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/**
 * A physical item control backed by slots, keyed cells or a network. Ports report native capabilities and
 * effects. The inventory layer decides whether to use a port as a colony store.
 *
 * <p>Resolve ports afresh on the server thread. Simulation predicts a transfer while the world stays unchanged.
 * Colony stock accounting coordinates batch transfers independently of facility routine leases.
 */
public interface ItemPort extends Port<ItemStack> {

    /** A bounded read of the port's goods and room. A port that cannot honor a window returns an empty reading. */
    PortReader reader(ServerLevel level, Optional<SlotWindow> window);

    /**
     * Takes the requested goods out, all of them or none. With `simulate`, nothing changes and the answer is what the
     * real call would do now.
     */
    TransferResult take(ServerLevel level, List<Pull> requested, Optional<SlotWindow> window, boolean simulate);

    /** Puts the goods in, all of them or none. */
    TransferResult put(ServerLevel level, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate);

    /**
     * An extra access point with no stock of its own. Present (even when empty) declares a proxy; members are
     * independent, non-proxy stores in the same realm. Resolve again to observe network changes.
     */
    default Optional<List<WorldPos>> proxyMembers() {
        return Optional.empty();
    }

    @Override
    default ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    default Optional<Stances> stance(ServerLevel level) {
        return valid(level) ? Control.reach(level, anchor()) : Optional.empty();
    }

    @Override
    default List<ItemStack> contents(ServerLevel level) {
        PortReader read = reader(level, Optional.empty());
        return read.step(128) ? read.result().entries().stream().map(entry ->
            entry.prototype().copyWithCount((int) Math.min(Integer.MAX_VALUE, entry.count()))).toList() : List.of();
    }

    /** One stack put in, all or none. */
    @Override
    default ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (!valid(level) || offered.isEmpty() || !operations().contains(Operation.PUT)) {
            return ItemStack.EMPTY;
        }
        return put(level, List.of(offered.copy()), Optional.empty(), simulate).done() ? offered.copy() : ItemStack.EMPTY;
    }

    /** One exact stack taken out for a routine, all or none. */
    @Override
    default ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        if (!valid(level) || wanted.isEmpty() || !operations().contains(Operation.TAKE)) {
            return ItemStack.EMPTY;
        }
        return take(level, List.of(new Pull.Exact(wanted)), Optional.empty(), simulate) instanceof TransferResult.Done done
            && !done.moved().isEmpty() ? wanted.copy() : ItemStack.EMPTY;
    }
}
