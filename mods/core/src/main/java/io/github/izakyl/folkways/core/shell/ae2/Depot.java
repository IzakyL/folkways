package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.networking.crafting.ICraftingCPU;
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

record Depot(UUID key, WorkSite site, Ae2Devices.NetworkKey network, Tally tally, Colony colony,
             OrderedOutputs outputs, Set<ItemSpec> craftables, List<Tally.Cpu> cpus,
             List<Device> devices, CraftQuotes.Snapshot<Ae2Devices.NetworkKey> quotes, int cpuLimit)
        implements NetworkDepot<Ae2Devices.NetworkKey, Tally.Cpu, ICraftingCPU> {

    @Override
    public ResourceLocation id() {
        return Ae2Integration.OWNER;
    }

    @Override
    public int limit() {
        return cpuLimit;
    }

    @Override
    public TakeNode.Source<?> collection() {
        return Ae2Devices.COLLECTION;
    }

    @Override
    public List<Tally.Cpu> candidates(ItemSpec item, CraftQuotes.Quote quote) {
        return cpus.stream().filter(cpu -> cpu.bytes() >= quote.bytes()).toList();
    }

    @Override
    public boolean free(Tally.Cpu executor) {
        return tally.free(executor);
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
