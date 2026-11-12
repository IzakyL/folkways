package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.autocrafting.AutocraftingNetworkComponent;
import com.refinedmods.refinedstorage.api.network.energy.EnergyNetworkComponent;
import com.refinedmods.refinedstorage.api.network.impl.node.AbstractNetworkNode;
import com.refinedmods.refinedstorage.api.network.impl.node.externalstorage.ExternalStorageNetworkNode;
import com.refinedmods.refinedstorage.api.network.impl.node.storage.StorageNetworkNode;
import com.refinedmods.refinedstorage.api.network.node.GraphNetworkComponent;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.storage.StateTrackedStorage;
import com.refinedmods.refinedstorage.api.storage.Storage;
import com.refinedmods.refinedstorage.api.storage.composite.CompositeStorage;
import com.refinedmods.refinedstorage.api.storage.limited.LimitedStorage;
import com.refinedmods.refinedstorage.common.api.storage.SerializableStorage;
import com.refinedmods.refinedstorage.common.storage.StorageTypes;
import com.refinedmods.refinedstorage.common.storagemonitor.StorageMonitorBlockEntity;
import com.refinedmods.refinedstorage.common.support.resource.ItemResource;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftedPort;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

final class NetworkPort implements StorePort {

    private final ServerLevel level;
    private final BlockPos pos;
    private final AbstractNetworkNode node;
    private final Set<Operation> operations;
    private final boolean craft;
    private final boolean buffer;

    NetworkPort(ServerLevel level, BlockPos pos, AbstractNetworkNode node, Set<Operation> operations,
                boolean craft, boolean buffer) {
        this.level = level;
        this.pos = pos.immutable();
        this.node = node;
        this.operations = Set.copyOf(operations);
        this.craft = craft;
        this.buffer = buffer;
    }

    AbstractNetworkNode node() {
        return node;
    }

    boolean crafts() {
        return craft;
    }

    boolean buffer() {
        return buffer;
    }

    StorageNetworkComponent storage() {
        return node.getNetwork().getComponent(StorageNetworkComponent.class);
    }

    AutocraftingNetworkComponent crafting() {
        return node.getNetwork().getComponent(AutocraftingNetworkComponent.class);
    }

    boolean allows(ItemResource item) {
        if (!(level.getBlockEntity(pos) instanceof StorageMonitorBlockEntity monitor)) {
            return true;
        }
        var configured = monitor.getConfiguredResource();
        return item.equals(configured) || monitor.isFuzzyMode() && configured instanceof ItemResource filter
            && item.normalize().equals(filter.normalize());
    }

    Optional<ItemResource> filter() {
        if (level.getBlockEntity(pos) instanceof StorageMonitorBlockEntity monitor
                && monitor.getConfiguredResource() instanceof ItemResource item) {
            return Optional.of(item);
        }
        return Optional.empty();
    }

    boolean fuzzy() {
        return level.getBlockEntity(pos) instanceof StorageMonitorBlockEntity monitor && monitor.isFuzzyMode();
    }

    boolean allowsCraft(ItemResource item) {
        return filter().map(item::equals).orElse(true);
    }

    StorePort delivery() {
        return new CraftedPort(this, (item, count, simulate) ->
            allowsCraft(ItemResource.ofItemStack(item)) ? storage().insert(ItemResource.ofItemStack(item), count,
                simulate ? Action.SIMULATE : Action.EXECUTE, Devices.source(node)) : 0);
    }

    void cancel(java.util.UUID task) {
        crafting().getPatterns().stream().map(pattern -> crafting().getProviderByPattern(pattern))
            .filter(java.util.Objects::nonNull).distinct().filter(provider -> provider.getTaskStatuses().stream()
                .anyMatch(status -> status.info().id().id().equals(task)))
            .forEach(provider -> provider.cancelTask(
                new com.refinedmods.refinedstorage.api.autocrafting.task.TaskId(task)));
    }

    @Override
    public PortIdentity identity() {
        WorldPos at = WorldPos.of(level, pos);
        return node == null || node.getNetwork() == null ? PortIdentity.block(at)
            : new PortIdentity(Devices.key(node.getNetwork()), at);
    }

    @Override
    public Usability usability() {
        var condemned = StorePorts.condemned(WorldPos.of(level, pos));
        if (condemned.isPresent()) {
            return new Usability.Unusable(condemned.get(), true);
        }
        if (buffer) {
            return Usability.because(Devices.reason("buffer"));
        }
        if (node == null || node.getNetwork() == null) {
            return Usability.because(Devices.reason("disconnected"));
        }
        if (!node.isActive() || node.getNetwork().getComponent(EnergyNetworkComponent.class).getStored() == 0) {
            return Usability.because(Devices.reason("unpowered"));
        }
        for (var member : node.getNetwork().getComponent(GraphNetworkComponent.class).getContainers()) {
            var device = member.getNode();
            boolean voids = device instanceof StorageNetworkNode storage && storage.isActive()
                && storage.getStorageConfiguration().isVoidExcess()
                || device instanceof ExternalStorageNetworkNode external && external.isActive()
                    && external.getStorageConfiguration().isVoidExcess();
            if (voids) {
                return Usability.because(Devices.reason("voiding"));
            }
        }
        if (level.getBlockEntity(pos) instanceof StorageMonitorBlockEntity monitor
                && !(monitor.getConfiguredResource() instanceof ItemResource)) {
            return Usability.because(Devices.reason("unconfigured"));
        }
        return Usability.USABLE;
    }

    @Override
    public Set<Operation> operations() {
        return operations;
    }

    @Override
    public OptionalLong revision() {
        return node == null || node.getNetwork() == null ? OptionalLong.empty()
            : OptionalLong.of(NetworkEvents.revision(level, pos, storage()));
    }

    List<PortReader.Entry> stock() {
        return storage().getAll().stream()
            .filter(entry -> entry.resource() instanceof ItemResource && entry.amount() > 0)
            .map(entry -> new PortReader.Entry(((ItemResource) entry.resource()).toItemStack(), entry.amount()))
            .toList();
    }

    @Override
    public PortReader reader(ServerLevel world, List<StoreRule> rules) {
        boolean usable = usability().usable();
        var cursor = (usable ? stock() : List.<PortReader.Entry>of()).iterator();
        return new PortReader() {
            private final List<Entry> entries = new ArrayList<>();
            private boolean finished;

            @Override
            public boolean step(int budget) {
                for (int work = 0; work < budget && cursor.hasNext(); work++) {
                    entries.add(cursor.next());
                }
                finished = !cursor.hasNext();
                return finished;
            }

            @Override
            public PortReading result() {
                if (!finished) {
                    throw new IllegalStateException("network reading is not finished");
                }
                return new PortReading(entries, Optional.empty(), usable ? room() : Room.NONE, Set.of(), true);
            }
        };
    }

    Room.Pool room() {
        long items = 0;
        Map<ItemSpec, Long> limits = new LinkedHashMap<>();
        Set<Storage> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var member : node.getNetwork().getComponent(GraphNetworkComponent.class).getContainers()) {
            if (member.getNode() instanceof StorageNetworkNode storage && storage.isActive()
                    && !storage.getStorageConfiguration().getAccessMode().isExtractOnly()) {
                long free = roomIn(storage.getStorage(), seen);
                items = free > Long.MAX_VALUE - items ? Long.MAX_VALUE : items + free;
                if (free > 0) {
                    for (var item : BuiltInRegistries.ITEM) {
                        ItemStack stack = item.getDefaultInstance();
                        if (stack.isEmpty()) {
                            continue;
                        }
                        ItemResource key = ItemResource.ofItemStack(stack);
                        if (storage.getStorageConfiguration().isAllowed(key)) {
                            limits.merge(Goods.specOf(stack), free,
                                (one, other) -> other > Long.MAX_VALUE - one ? Long.MAX_VALUE : one + other);
                        }
                    }
                }
            }
        }
        // Configured child proxies only support composite operations. Simulation must enter through the root.
        limits.replaceAll((goods, limit) -> Math.min(limit, storage().insert(
            ItemResource.ofItemStack(Goods.display(goods)), limit, Action.SIMULATE, Devices.source(node))));
        return new Room.Pool(items, items, Optional.of(limits));
    }

    private static long roomIn(Storage storage, Set<Storage> seen) {
        if (!seen.add(storage)) {
            return 0;
        }
        if (storage instanceof StateTrackedStorage tracked) {
            return roomIn(tracked.getDelegate(), seen);
        }
        if (storage instanceof SerializableStorage serial && serial.getType() == StorageTypes.ITEM
                && storage instanceof LimitedStorage limited) {
            return Math.max(0, limited.getCapacity() - storage.getStored());
        }
        if (storage instanceof CompositeStorage composite) {
            long free = 0;
            for (var child : composite.getSources()) {
                long room = roomIn(child, seen);
                free = room > Long.MAX_VALUE - free ? Long.MAX_VALUE : free + room;
            }
            return free;
        }
        // External, creative and unknown providers contribute stock, but no promised item capacity.
        return 0;
    }

    @Override
    public TransferResult take(ServerLevel world, List<Pull> requested, List<StoreRule> rules, boolean simulate) {
        if (!usability().usable()) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!operations.contains(Operation.TAKE) || !StoreRule.takes(rules)
                || rules.stream().anyMatch(StoreRule.Slots.class::isInstance)
                || !requested.stream().allMatch(pull -> admits(rules, pull))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return Transfers.take(level, pos, storage(), Devices.source(node), requested, this::allows, simulate);
    }

    @Override
    public TransferResult put(ServerLevel world, List<ItemStack> goods, List<StoreRule> rules,
                              boolean directed, boolean simulate) {
        if (!usability().usable()) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!operations.contains(Operation.PUT) || rules.stream().anyMatch(StoreRule.Slots.class::isInstance)
                || goods.stream().anyMatch(stack -> !stack.isEmpty() && !allowsCraft(ItemResource.ofItemStack(stack)))
                || !directed && (!StoreRule.stores(rules)
                    || goods.stream().anyMatch(stack -> !StoreRule.admits(rules, stack)))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return Transfers.put(level, pos, storage(), Devices.source(node), goods, room(), simulate);
    }

    // On a network device the panel's item filter bounds taking as well as putting.
    private static boolean admits(List<StoreRule> rules, Pull pull) {
        return pull instanceof Pull.Exact exact ? StoreRule.admits(rules, exact.stack())
            : StoreRule.admits(rules, ((Pull.Matching) pull).spec());
    }
}
