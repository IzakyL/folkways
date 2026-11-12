package io.github.izakyl.folkways.core.inventory.api.port;

import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;

/**
 * One physical store as the colony may use it, whatever the block behind it keeps its goods in: slots, keyed bulk
 * cells or a whole network.
 *
 * <p>A port is resolved afresh from the world whenever it is needed and is not kept between ticks. Everything it
 * promises holds only on the server thread and only until the world changes.
 *
 * <p>The contract the colony plans on:
 * <ul>
 *   <li><b>Conservation.</b> Goods put in come out unchanged in kind, count and components. Nothing put in is
 *       destroyed, converted or joined by goods that were not there.</li>
 *   <li><b>Predictability.</b> A simulated transfer foretells the real one exactly, as long as nothing else changes
 *       the store between the two calls.</li>
 *   <li><b>All or nothing.</b> A real transfer either moves every requested stack or leaves the store as it was.</li>
 *   <li><b>No hidden work.</b> The store does not change its own goods by itself (smelting, crafting, compacting,
 *       voiding). Goods moved in or out by something outside, a pipe or an upgrade that only moves items, are
 *       allowed: the colony notices the change and plans again.</li>
 * </ul>
 * A port that cannot keep the contract reports {@link Usability.Unusable} instead of guessing.
 */
public interface StorePort {

    /** What physical store this is. Ports with equal identities are views of the same goods and are counted once. */
    PortIdentity identity();

    /** Whether residents may use the store now, re-decided on every observation. */
    Usability usability();

    /** What residents may do at this block. A chest allows both; a network device allows what its kind offers. */
    Set<Operation> operations();

    /** A bounded, resumable read of the store's goods and room. */
    PortReader reader(ServerLevel level, List<StoreRule> rules);

    /**
     * Takes the requested goods out, all of them or none. With `simulate`, nothing changes and the answer is what the
     * real call would do now.
     */
    TransferResult take(ServerLevel level, List<Pull> requested, List<StoreRule> rules, boolean simulate);

    /** Puts the goods in, all of them or none; `directed` bypasses {@link StoreRule.Admit} as orders do. */
    TransferResult put(ServerLevel level, List<net.minecraft.world.item.ItemStack> goods, List<StoreRule> rules,
                       boolean directed, boolean simulate);

    /**
     * A cheap number that moves whenever the goods may have changed, when the store can tell. Empty means the colony
     * relies on block-entity change events and {@link StorePorts#changed} instead.
     */
    default OptionalLong revision() {
        return OptionalLong.empty();
    }

    /**
     * An extra access point with no stock of its own. Present (even when empty) declares a proxy; members are
     * independent, non-proxy stores in the same realm. Resolve again to observe network changes.
     */
    default Optional<List<WorldPos>> proxyMembers() {
        return Optional.empty();
    }

    enum Operation {
        TAKE,
        PUT
    }
}
