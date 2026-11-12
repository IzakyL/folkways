package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import io.github.izakyl.folkways.core.shell.crafting.NetworkBuffers;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** One off-world provider per colony/network, attached to an already enrolled device. */
final class ColonyProvider implements ICraftingProvider {

    final WorldPos device;
    final IGridNode anchor;
    final IManagedGridNode node;
    private final IGridConnection connection;
    private final NetworkPort port;
    private final NetworkBuffers buffers;
    private List<IPatternDetails> patterns = List.of();
    private int pushes;

    ColonyProvider(ServerLevel level, NetworkPort port, NetworkBuffers buffers) {
        this.port = port;
        this.buffers = buffers;
        device = port.identity().home();
        anchor = port.node();
        node = GridHelper.createManagedNode(this, new IGridNodeListener<ColonyProvider>() {
            @Override
            public void onSaveChanges(ColonyProvider owner, IGridNode node) {
            }
        }).setInWorldNode(false).setIdlePowerUsage(0).setFlags()
            .addService(ICraftingProvider.class, this);
        node.setOwningPlayerId(anchor.getOwningPlayerId());
        node.create(level, device.block(level));
        connection = GridHelper.createConnection(node.getNode(), anchor);
    }

    void patterns(List<IPatternDetails> offered) {
        if (!patterns.equals(offered)) {
            patterns = List.copyOf(offered);
            ICraftingProvider.requestUpdate(node);
        }
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    @Override
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
        if (!patterns.contains(pattern) || !node.isActive()) {
            return false;
        }
        if (!(pattern instanceof ColonyPattern colonyPattern) || isBusy()) {
            return false;
        }
        List<ItemStack> goods = new ArrayList<>();
        for (var input : inputs) {
            for (var amount : input) {
                if (!(amount.getKey() instanceof AEItemKey item)) {
                    return false;
                }
                goods.addAll(NetworkBuffers.stacks(item.toStack(), amount.getLongValue()));
            }
        }
        if (!buffers.accept(device, anchor.getGrid(), colonyPattern.reference(), goods)) {
            return false;
        }
        pushes++;
        return true;
    }

    @Override
    public boolean isBusy() {
        return buffers.busy();
    }

    int pushes() {
        return pushes;
    }

    long buffered(AEKey item) {
        return item instanceof AEItemKey key ? buffers.count(device, key.toStack()) : 0;
    }

    void destroy() {
        connection.destroy();
        node.destroy();
    }
}
