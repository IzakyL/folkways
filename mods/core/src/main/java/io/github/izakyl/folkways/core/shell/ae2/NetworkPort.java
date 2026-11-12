package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.IBasicCellItem;
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
    private final IGridNode node;
    private final Set<Operation> operations;
    private final boolean craft;
    private final boolean ambiguous;
    private final boolean buffer;

    NetworkPort(ServerLevel level, BlockPos pos, IGridNode node, Set<Operation> operations,
                boolean craft, boolean ambiguous, boolean buffer) {
        this.level = level;
        this.pos = pos.immutable();
        this.node = node;
        this.operations = Set.copyOf(operations);
        this.craft = craft;
        this.ambiguous = ambiguous;
        this.buffer = buffer;
    }

    IGridNode node() {
        return node;
    }

    boolean crafts() {
        return craft;
    }

    boolean buffer() {
        return buffer;
    }

    MEStorage storage() {
        return node.getGrid().getStorageService().getInventory();
    }

    StorePort delivery() {
        return new CraftedPort(this, (item, count, simulate) ->
            storage().insert(AEItemKey.of(item), count, simulate ? Actionable.SIMULATE
                : Actionable.MODULATE, Devices.source(node)));
    }

    void cancel(java.util.UUID task) {
        for (var cpu : node.getGrid().getCraftingService().getCpus()) {
            if (cpu instanceof appeng.me.cluster.implementations.CraftingCPUCluster cluster) {
                var link = cluster.craftingLogic.getLastLink();
                if (link != null && link.getCraftingID().equals(task)) {
                    cpu.cancelJob();
                }
            }
        }
    }

    @Override
    public PortIdentity identity() {
        WorldPos at = WorldPos.of(level, pos);
        return node == null ? PortIdentity.block(at) : new PortIdentity(Devices.key(node), at);
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
        if (ambiguous) {
            return Usability.because(Devices.reason("ambiguous"));
        }
        if (node == null) {
            return Usability.because(Devices.reason("disconnected"));
        }
        if (!node.isPowered()) {
            return Usability.because(Devices.reason("unpowered"));
        }
        if (!node.meetsChannelRequirements()) {
            return Usability.because(Devices.reason("no_channel"));
        }
        if (!node.isActive()) {
            return Usability.because(Devices.reason("booting"));
        }
        return Usability.USABLE;
    }

    @Override
    public Set<Operation> operations() {
        return operations;
    }

    @Override
    public OptionalLong revision() {
        // AE2 19 exposes a cached inventory, but no public inventory-change listener. Bound staleness to one second.
        return OptionalLong.of(level.getGameTime() / 20);
    }

    @Override
    public PortReader reader(ServerLevel world, List<StoreRule> rules) {
        boolean usable = usability().usable();
        var cursor = usable ? storage().getAvailableStacks().iterator()
            : new KeyCounter().iterator();
        return new PortReader() {
            private final List<Entry> entries = new ArrayList<>();
            private boolean finished;

            @Override
            public boolean step(int budget) {
                for (int work = 0; work < budget && cursor.hasNext(); work++) {
                    var entry = cursor.next();
                    if (entry.getKey() instanceof AEItemKey item && entry.getLongValue() > 0) {
                        entries.add(new Entry(item.toStack(), entry.getLongValue()));
                    }
                }
                finished = !cursor.hasNext();
                return finished;
            }

            @Override
            public PortReading result() {
                if (!finished) {
                    throw new IllegalStateException("network reading is not finished");
                }
                return new PortReading(entries, Optional.empty(), usable ? room() : Room.NONE, Set.of(), false);
            }
        };
    }

    Room.Pool room() {
        long items = 0;
        long types = Long.MAX_VALUE;
        long existing = storage().getAvailableStacks().keySet().size();
        Map<ItemSpec, Long> limits = new LinkedHashMap<>();
        for (IGridNode member : node.getGrid().getNodes()) {
            if (member.isActive() && member.getOwner() instanceof IChestOrDrive drive) {
                for (int slot = 0; slot < drive.getCellCount(); slot++) {
                    if (!(drive.getCellItem(slot) instanceof IBasicCellItem cell)
                            || cell.getKeyType() != AEKeyType.items()) {
                        continue;
                    }
                    var inventory = drive.getOriginalCellInventory(slot);
                    if (inventory == null) {
                        continue;
                    }
                    ItemStack definition = new ItemStack(drive.getCellItem(slot));
                    var contents = inventory.getAvailableStacks();
                    long kinds = cell.getTotalTypes(definition) - contents.keySet().size();
                    if (kinds < existing) {
                        continue;
                    }
                    // Public cell geometry plus native simulations: reserve every type's overhead, including
                    // network kinds on other cells. Partition and upgrade filters are applied by insert itself.
                    long stored = 0;
                    for (var entry : contents) {
                        stored = Math.addExact(stored, entry.getLongValue());
                    }
                    long free = Math.max(0, cell.getBytes(definition) * 8L - stored
                        - cell.getTotalTypes(definition) * cell.getBytesPerType(definition) * 8L);
                    items = Math.addExact(items, free);
                    types = Math.min(types, kinds - existing);
                    for (var item : BuiltInRegistries.ITEM) {
                        ItemStack stack = item.getDefaultInstance();
                        if (!stack.isEmpty()) {
                            long accepted = inventory.insert(AEItemKey.of(stack), free,
                                Actionable.SIMULATE, Devices.source(node));
                            if (accepted > 0) {
                                limits.merge(Goods.specOf(stack), accepted, Math::addExact);
                            }
                        }
                    }
                }
            }
        }
        return new Room.Pool(items, items == 0 ? 0 : types, Optional.of(limits));
    }

    private long typeCost() {
        long cost = 0;
        for (IGridNode member : node.getGrid().getNodes()) {
            if (member.getOwner() instanceof IChestOrDrive drive) {
                for (int slot = 0; slot < drive.getCellCount(); slot++) {
                    if (drive.getCellItem(slot) instanceof IBasicCellItem cell) {
                        cost = Math.max(cost, cell.getBytesPerType(new ItemStack(drive.getCellItem(slot))) * 8L);
                    }
                }
            }
        }
        return cost;
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
        return Transfers.take(level, pos, storage(), Devices.source(node), requested, simulate);
    }

    @Override
    public TransferResult put(ServerLevel world, List<ItemStack> goods, List<StoreRule> rules,
                              boolean directed, boolean simulate) {
        if (!usability().usable()) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!operations.contains(Operation.PUT) || rules.stream().anyMatch(StoreRule.Slots.class::isInstance)
                || !directed && (!StoreRule.stores(rules)
                    || goods.stream().anyMatch(stack -> !StoreRule.admits(rules, stack)))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return Transfers.put(level, pos, storage(), Devices.source(node), goods, room(), typeCost(), simulate);
    }

    // On a network device the panel's item filter bounds taking as well as putting.
    private static boolean admits(List<StoreRule> rules, Pull pull) {
        return pull instanceof Pull.Exact exact ? StoreRule.admits(rules, exact.stack())
            : StoreRule.admits(rules, ((Pull.Matching) pull).spec());
    }
}
