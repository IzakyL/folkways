package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.Settings;
import appeng.api.implementations.blockentities.IChestOrDrive;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.api.storage.cells.IBasicCellItem;
import appeng.parts.storagebus.StorageBusPart;
import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.PortIdentity;
import io.github.izakyl.folkways.core.facility.api.control.PortReader;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.Room;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.facility.api.ExternalAttachments;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.shell.crafting.CraftedPort;
import io.github.izakyl.folkways.core.shell.crafting.ExternalRoom;
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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

public final class NetworkPort implements ItemPort {

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

    public IGridNode node() {
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

    public ItemPort delivery() {
        return new CraftedPort(this, (item, count, simulate) ->
            storage().insert(AEItemKey.of(item), count, simulate ? Actionable.SIMULATE
                : Actionable.MODULATE, Ae2Devices.source(node)));
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
    public WorldPos anchor() {
        return identity().home();
    }

    @Override
    public PortIdentity identity() {
        WorldPos at = WorldPos.of(level, pos);
        if (node == null || node.getGrid() == null) {
            return PortIdentity.block(at);
        }
        var group = ExternalAttachments.group(level, node.getGrid());
        return new PortIdentity(group.size() > 1 ? group : craftingKey(), at);
    }

    public Ae2Devices.NetworkKey craftingKey() {
        return Ae2Devices.key(node);
    }

    @Override
    public Optional<Component> unavailable(ServerLevel level) {
        if (buffer) {
            return Optional.of(Ae2Devices.reason("buffer"));
        }
        if (ambiguous) {
            return Optional.of(Ae2Devices.reason("ambiguous"));
        }
        if (node == null) {
            return Optional.of(Ae2Devices.reason("disconnected"));
        }
        if (!node.isPowered()) {
            return Optional.of(Ae2Devices.reason("unpowered"));
        }
        if (!node.meetsChannelRequirements()) {
            return Optional.of(Ae2Devices.reason("no_channel"));
        }
        if (!node.isActive()) {
            return Optional.of(Ae2Devices.reason("booting"));
        }
        return Optional.empty();
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

    long count(ItemSpec wanted) {
        long amount = 0;
        for (var entry : storage().getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey item && Goods.matches(item.toStack(), wanted)) {
                amount += entry.getLongValue();
            }
        }
        return amount;
    }

    @Override
    public PortReader reader(ServerLevel world, Optional<SlotWindow> window) {
        if (window.isPresent()) {
            return PortReader.EMPTY;
        }
        boolean usable = valid(world);
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
        var existingKeys = storage().getAvailableStacks().keySet();
        long existing = existingKeys.size();
        var group = ExternalAttachments.group(level, node.getGrid());
        Map<ItemSpec, Long> limits = new LinkedHashMap<>();
        ExternalRoom external = new ExternalRoom();
        var source = Ae2Devices.source(node);
        for (IGridNode member : node.getGrid().getNodes()) {
            if (member.isActive() && member.getOwner() instanceof StorageBusPart bus
                    && bus.getLevel() instanceof ServerLevel busLevel
                    && bus.getConfigManager().getSetting(Settings.ACCESS).isAllowInsertion()) {
                var side = bus.getSide();
                BlockPos target = bus.getBlockEntity().getBlockPos().relative(side);
                Object connected = ExternalStorage.networkAt(busLevel, target, side.getOpposite());
                if (connected != null && group.contains(connected)) {
                    continue;
                }
                bus.mountInventories((mounted, priority) -> external.add(busLevel, target, side.getOpposite(),
                    (stack, limit) -> mounted.insert(AEItemKey.of(stack), limit, Actionable.SIMULATE, source)));
            }
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
                                Actionable.SIMULATE, source);
                            if (accepted > 0) {
                                limits.merge(Goods.specOf(stack), accepted, Math::addExact);
                            }
                        }
                    }
                }
            }
        }
        long externalItems = external.mergeInto(limits);
        items = ExternalRoom.add(items, externalItems);
        long externalTypes = external.newKindRoom(goods -> !existingKeys.contains(AEItemKey.of(Goods.display(goods))));
        types = ExternalRoom.add(types == Long.MAX_VALUE ? 0 : types, externalTypes);
        limits.replaceAll((goods, limit) -> Math.min(limit, storage().insert(
            AEItemKey.of(Goods.display(goods)), limit, Actionable.SIMULATE, source)));
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
    public TransferResult take(ServerLevel world, List<Pull> requested, Optional<SlotWindow> window, boolean simulate) {
        if (!valid(world)) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!operations.contains(Operation.TAKE)
                || window.isPresent()) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return Transfers.take(level, pos, storage(), Ae2Devices.source(node), requested, simulate);
    }

    @Override
    public TransferResult put(ServerLevel world, List<ItemStack> goods, Optional<SlotWindow> window, boolean simulate) {
        if (!valid(world)) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!operations.contains(Operation.PUT) || window.isPresent()) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return Transfers.put(level, pos, storage(), Ae2Devices.source(node), goods, room(), typeCost(), simulate);
    }

}
