package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.autocrafting.PatternProvider;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftQuotes;
import io.github.izakyl.folkways.core.shell.crafting.NetworkDepot;
import io.github.izakyl.folkways.core.shell.crafting.OrderedOutputs;
import io.github.izakyl.folkways.core.shell.crafting.TakeNode;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

record Depot(UUID key, WorkSite site, Rs2Devices.NetworkKey network, Tally tally, Colony colony,
             OrderedOutputs outputs, Set<ItemSpec> craftables, List<Tally.Executor> executors,
             List<Device> devices, CraftQuotes.Snapshot<Rs2Devices.NetworkKey> quotes, int taskLimit)
        implements NetworkDepot<Rs2Devices.NetworkKey, Tally.Executor, PatternProvider> {

    @Override
    public ResourceLocation id() {
        return Rs2Integration.OWNER;
    }

    @Override
    public int limit() {
        return taskLimit;
    }

    @Override
    public TakeNode.Source<?> collection() {
        return Rs2Devices.COLLECTION;
    }

    @Override
    public List<Tally.Executor> candidates(ItemSpec item, CraftQuotes.Quote quote) {
        return executors.stream().filter(executor -> executor.outputs().contains(item)).toList();
    }

    @Override
    public boolean free(Tally.Executor executor) {
        return tally.free(network, executor);
    }

    @Override
    public CraftOrder order(WorldPos at, ItemSpec item, Tally.Ticket ticket) {
        return new CraftOrder(at, item, ticket, colony, outputs);
    }

    @Override
    public boolean orders(WorldPos at, ItemSpec item) {
        return NetworkPresence.orders(colony, at, item);
    }
}
