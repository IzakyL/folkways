package io.github.izakyl.folkways.core.shell.create.dispatch;

import com.google.common.collect.ImmutableListMultimap;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.logistics.BigItemStack;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlock;
import com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity;
import com.simibubi.create.content.logistics.packagePort.postbox.PostboxBlock;
import com.simibubi.create.content.logistics.packager.InventorySummary;
import com.simibubi.create.content.logistics.packager.PackagerBlockEntity;
import com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour;
import com.simibubi.create.content.logistics.packagerLink.LogisticsManager;
import com.simibubi.create.content.logistics.packagerLink.PackagerLinkBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import com.simibubi.create.content.logistics.stockTicker.StockTickerBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import io.github.izakyl.folkways.core.inventory.api.InventoryFeedback;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.apache.commons.lang3.mutable.MutableBoolean;

public final class CreatePackageNetwork implements PackageNetwork {

    private record Laid(ResourceKey<Level> realm, UUID network) {
    }

    private record Read(long tick, CreateLayout layout) {
    }

    // A network's layout is walked once a tick at most, however many ports ask after it.
    private final Map<Laid, Read> layouts = new ConcurrentHashMap<>();

    public void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) {
            layouts.keySet().removeIf(key -> event.getServer().getLevel(key.realm()) == null
                || !Create.LOGISTICS.logisticsNetworks.containsKey(key.network()));
        }
    }

    public void forgetServer() {
        layouts.clear();
    }

    @Override
    public Optional<CreateLayout> layoutOf(ServerLevel level, UUID network) {
        return Optional.of(readLayout(level, network));
    }

    private CreateLayout readLayout(ServerLevel level, UUID network) {
        long now = level.getGameTime();
        return layouts.compute(new Laid(level.dimension(), network), (key, read) ->
            read != null && read.tick() == now ? read : new Read(now, CreateLayout.of(level, network))).layout();
    }

    @Override
    public boolean isPort(BlockState state) {
        return state.getBlock() instanceof FrogportBlock || state.getBlock() instanceof PostboxBlock;
    }

    @Override
    public Optional<UUID> networkAt(ServerLevel level, BlockPos pos) {
        Optional<UUID> linked = linkAt(level, pos);
        if (linked.isPresent() || !level.isLoaded(pos)) {
            return linked;
        }
        BlockEntity found = level.getBlockEntity(pos);
        if (found instanceof PackagerBlockEntity) {
            return linkBeside(level, pos);
        }
        Optional<BlockPos> chain = found instanceof ChainConveyorBlockEntity ? Optional.of(pos)
            : found instanceof PackagePortBlockEntity port ? CreateLayout.chainOf(port) : Optional.empty();
        // A sending frogport takes no packages, so Create never lists it on its chain: walk out from each network instead.
        for (UUID network : Create.LOGISTICS.logisticsNetworks.keySet()) {
            CreateLayout layout = readLayout(level, network);
            if (layout.senders().contains(pos) || chain.filter(layout.chains()::contains).isPresent()) {
                return Optional.of(network);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<UUID> linkerAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockEntity found = level.getBlockEntity(pos);
        return found instanceof PackagerLinkBlockEntity || found instanceof StockTickerBlockEntity
            ? linkAt(level, pos) : Optional.empty();
    }

    @Override
    public Optional<BlockPos> chainAt(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockEntity found = level.getBlockEntity(pos);
        if (found instanceof ChainConveyorBlockEntity) {
            return Optional.of(pos.immutable());
        }
        return found instanceof FrogportBlockEntity port ? CreateLayout.chainOf(port) : Optional.empty();
    }

    @Override
    public boolean frogport(ServerLevel level, BlockPos pos) {
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof FrogportBlockEntity;
    }

    @Override
    public boolean isPostbox(BlockState state) {
        return state.getBlock() instanceof PostboxBlock;
    }

    @Override
    public Set<BlockPos> chainNet(ServerLevel level, BlockPos chain) {
        return CreateLayout.net(level, chain);
    }

    @Override
    public List<BlockPos> frogportsOn(ServerLevel level, Set<BlockPos> chains) {
        return CreatePorts.frogportsOn(level, chains);
    }

    @Override
    public List<BlockPos> sendersOf(ServerLevel level, UUID network) {
        return List.copyOf(readLayout(level, network).senders());
    }

    @Override
    public List<BlockPos> portsOn(ServerLevel level, Set<BlockPos> chains) {
        return CreateLayout.portsOn(level, chains);
    }

    @Override
    public boolean unpacks(ServerLevel level, BlockPos port) {
        return CreateLayout.unpacks(level, port);
    }

    @Override
    public Optional<BlockPos> chainOf(ServerLevel level, BlockPos port) {
        return portAt(level, port).flatMap(CreateLayout::chainOf);
    }

    @Override
    public double chainSpeed(ServerLevel level, BlockPos chain) {
        return CreateLayout.speedOf(level, chain);
    }

    @Override
    public OptionalInt chainTicks(ServerLevel level, List<BlockPos> starts, BlockPos end) {
        return CreateLayout.chainTicks(level, starts, end);
    }

    @Override
    public int packingTicks(ServerLevel level, CreateLayout layout) {
        return layout.packing(level);
    }

    @Override
    public List<BlockPos> containersOf(ServerLevel level, UUID network) {
        return readLayout(level, network).containers();
    }

    @Override
    public boolean containersComplete(ServerLevel level, UUID network) {
        return CreateLayout.containersComplete(level, network);
    }

    @Override
    public Container inventoryAt(ServerLevel level, BlockPos container) {
        IItemHandler held = handlerAt(level, container);
        return held == null ? null : new ShelfInventory(held);
    }

    @Override
    public List<ItemStack> takeOut(ServerLevel level, BlockPos container, Predicate<ItemStack> goods, long count) {
        IItemHandler held = handlerAt(level, container);
        if (held == null || count <= 0 || extract(held, goods, count, true).stream()
                .mapToLong(ItemStack::getCount).sum() < count) {
            return List.of();
        }
        return extract(held, goods, count, false);
    }

    private static List<ItemStack> extract(IItemHandler held, Predicate<ItemStack> goods, long count, boolean dry) {
        List<ItemStack> taken = new ArrayList<>();
        long left = count;
        for (int slot = 0; slot < held.getSlots() && left > 0; slot++) {
            ItemStack there = held.getStackInSlot(slot);
            if (there.isEmpty() || !goods.test(there)) {
                continue;
            }
            ItemStack out = held.extractItem(slot, (int) Math.min(left, there.getCount()), dry);
            if (!out.isEmpty()) {
                taken.add(out);
                left -= out.getCount();
            }
        }
        return List.copyOf(taken);
    }

    @Override
    public void post(ServerLevel level, BlockPos at, BlockPos postbox, IntPredicate acceptsOrder) {
        Optional<PackagePortBlockEntity> port = portAt(level, postbox);
        if (port.isPresent() && level.getBlockEntity(at) instanceof PackagerBlockEntity packager
                && packager.animationTicks == 0 && PackageItem.isPackage(packager.heldBox)
                && PackageItem.hasOrderData(packager.heldBox)
                && acceptsOrder.test(PackageItem.getOrderId(packager.heldBox))
                && ItemHandlerHelper.insertItem(port.get().inventory, packager.heldBox.copy(), true).isEmpty()) {
            ItemHandlerHelper.insertItem(port.get().inventory, packager.heldBox.copy(), false);
            packager.heldBox = ItemStack.EMPTY;
            packager.notifyUpdate();
            port.get().notifyUpdate();
            InventoryFeedback.store(level, WorldPos.of(level, postbox));
        }
    }

    @Override
    public List<BlockPos> railPortsReached(ServerLevel level, Collection<BlockPos> senders) {
        return CreateMail.reached(level, senders);
    }

    @Override
    public List<TrainArrival> trainsBetween(ServerLevel level, BlockPos from, BlockPos to,
            Function<UUID, List<TransitNetwork.Stop>> timetable) {
        return CreateMail.stationOf(level, from).flatMap(leaving -> CreateMail.stationOf(level, to)
            .map(bound -> CreateMail.trainsBetween(level.getServer(), leaving, bound, timetable))).orElseGet(List::of);
    }

    @Override
    public Optional<String> stationOf(ServerLevel level, BlockPos postbox) {
        return CreateMail.stationOf(level, postbox).map(station -> station.name);
    }

    @Override
    public Optional<Component> trainName(MinecraftServer server, UUID train) {
        return CreateMail.nameOf(server, train);
    }

    @Override
    public boolean giveBack(ServerLevel level, UUID network, BlockPos postbox, int order) {
        Optional<PackagePortBlockEntity> port = portAt(level, postbox);
        for (int slot = 0; port.isPresent() && slot < port.get().inventory.getSlots(); slot++) {
            ItemStack parcel = port.get().inventory.getStackInSlot(slot);
            if (!orderOf(parcel).equals(OptionalInt.of(order))) {
                continue;
            }
            for (BlockPos at : readLayout(level, network).packagers()) {
                if (level.getBlockEntity(at) instanceof PackagerBlockEntity packager
                        && packager.inventory.insertItem(0, parcel.copy(), true).isEmpty()) {
                    packager.inventory.insertItem(0, parcel.copy(), false);
                    port.get().inventory.setStackInSlot(slot, ItemStack.EMPTY);
                    port.get().notifyUpdate();
                    InventoryFeedback.store(level, WorldPos.of(level, postbox));
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    @Override
    public Optional<BlockPos> packerAt(ServerLevel level, BlockPos port) {
        Optional<PackagePortBlockEntity> found = portAt(level, port);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<BlockPos> beside = CreateMail.posts(found.get()) ? CreateLayout.packagersBeside(level, port)
            : CreateLayout.chainOf(found.get()).isPresent() ? CreateLayout.packagersBeside(level, port).stream()
                .filter(port.below()::equals).toList()
            : List.of();
        return beside.stream().filter(packager -> linkBeside(level, packager).isEmpty()).findFirst();
    }

    @Override
    public boolean mails(ServerLevel level, BlockPos port) {
        return portAt(level, port).filter(CreateMail::posts).isPresent();
    }

    @Override
    public OptionalInt throwTicks(ServerLevel level, BlockPos from, BlockPos to) {
        return CreateLayout.throwTicks(level, from, to);
    }

    @Override
    public Optional<ItemStack> pack(List<ItemStack> goods, String address, int order) {
        ItemStackHandler contents = new ItemStackHandler(PackageItem.SLOTS);
        for (ItemStack stack : goods) {
            if (!ItemHandlerHelper.insertItemStacked(contents, stack.copy(), false).isEmpty()) {
                return Optional.empty();
            }
        }
        ItemStack parcel = PackageItem.containing(contents);
        PackageItem.addAddress(parcel, address);
        PackageItem.setOrder(parcel, order, 0, true, 0, true, PackageOrderWithCrafts.empty());
        return Optional.of(parcel);
    }

    @Override
    public boolean send(ServerLevel level, BlockPos port, ItemStack parcel) {
        Optional<PackagePortBlockEntity> found = portAt(level, port);
        if (found.isEmpty()) {
            return false;
        }
        for (int slot = 0; slot < found.get().inventory.getSlots(); slot++) {
            if (found.get().inventory.getStackInSlot(slot).isEmpty()) {
                found.get().inventory.setStackInSlot(slot, parcel.copy());
                found.get().notifyUpdate();
                InventoryFeedback.store(level, WorldPos.of(level, port));
                return true;
            }
        }
        return false;
    }

    private static IItemHandler handlerAt(ServerLevel level, BlockPos at) {
        return level.isLoaded(at) ? level.getCapability(Capabilities.ItemHandler.BLOCK, at, null) : null;
    }

    private static Optional<UUID> linkAt(ServerLevel level, BlockPos pos) {
        LogisticallyLinkedBehaviour link =
            BlockEntityBehaviour.get(level, pos, LogisticallyLinkedBehaviour.TYPE);
        return link == null ? Optional.empty() : Optional.ofNullable(link.freqId);
    }

    private static Optional<UUID> linkBeside(ServerLevel level, BlockPos packager) {
        for (Direction side : Direction.values()) {
            BlockPos beside = packager.relative(side);
            if (level.isLoaded(beside)) {
                Optional<UUID> network = linkAt(level, beside);
                if (network.isPresent()) {
                    return network;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean mayAdministrate(MinecraftServer server, UUID network, ServerPlayer player) {
        return Create.LOGISTICS.mayAdministrate(network, player);
    }

    @Override
    public List<Supply> summaryOf(MinecraftServer server, UUID network) {
        InventorySummary summary = LogisticsManager.getSummaryOfNetwork(network, true);
        List<Supply> lots = new ArrayList<>();
        for (BigItemStack lot : summary.getStacks()) {
            if (lot.count > 0) {
                lots.add(new Supply(lot.stack.copyWithCount(1), lot.count));
            }
        }
        return List.copyOf(lots);
    }

    @Override
    public boolean exists(MinecraftServer server, UUID network) {
        return Create.LOGISTICS.logisticsNetworks.containsKey(network);
    }

    @Override
    public String addressOf(ServerLevel level, BlockPos at) {
        return portAt(level, at).map(port -> port.addressFilter).orElse("");
    }

    // A port whose filter could not take its alias has no route, and the colony sends it nothing.
    @Override
    public String routeOf(ServerLevel level, BlockPos at) {
        String alias = PortAliases.of(at);
        return portAt(level, at)
            .filter(port -> port.getFilterString() != null && port.getFilterString().contains(alias))
            .map(port -> alias)
            .orElse("");
    }

    @Override
    public boolean portOpen(ServerLevel level, BlockPos at) {
        return portAt(level, at).filter(port -> port.acceptsPackages && !port.isBackedUp()).isPresent();
    }

    @Override
    public List<Parcel> parcelsAt(ServerLevel level, BlockPos at) {
        Optional<PackagePortBlockEntity> port = portAt(level, at);
        if (port.isEmpty()) {
            return List.of();
        }
        List<Parcel> parcels = new ArrayList<>();
        for (int slot = 0; slot < port.get().inventory.getSlots(); slot++) {
            ItemStack stack = port.get().inventory.getStackInSlot(slot);
            if (PackageItem.isPackage(stack)) {
                parcels.add(parcelOf(stack));
            }
        }
        return List.copyOf(parcels);
    }

    @Override
    public List<ItemStack> collectFrom(ServerLevel level, BlockPos at, int order) {
        Optional<PackagePortBlockEntity> port = portAt(level, at);
        if (port.isEmpty()) {
            return List.of();
        }
        List<ItemStack> taken = new ArrayList<>();
        for (int slot = 0; slot < port.get().inventory.getSlots(); slot++) {
            ItemStack stack = port.get().inventory.getStackInSlot(slot);
            if (PackageItem.isPackage(stack) && orderOf(stack).equals(OptionalInt.of(order))) {
                port.get().inventory.setStackInSlot(slot, ItemStack.EMPTY);
                taken.addAll(contentsOf(stack));
            }
        }
        if (!taken.isEmpty()) {
            port.get().notifyUpdate();
            InventoryFeedback.store(level, WorldPos.of(level, at));
        }
        return List.copyOf(taken);
    }

    @Override
    public Optional<List<ItemStack>> clearOne(ServerLevel level, BlockPos at, Predicate<Parcel> stray) {
        Optional<PackagePortBlockEntity> port = portAt(level, at);
        if (port.isEmpty()) {
            return Optional.empty();
        }
        for (int slot = 0; slot < port.get().inventory.getSlots(); slot++) {
            ItemStack stack = port.get().inventory.getStackInSlot(slot);
            if (PackageItem.isPackage(stack) && stray.test(parcelOf(stack))) {
                port.get().inventory.setStackInSlot(slot, ItemStack.EMPTY);
                port.get().notifyUpdate();
                InventoryFeedback.store(level, WorldPos.of(level, at));
                return Optional.of(contentsOf(stack));
            }
        }
        return Optional.empty();
    }

    @Override
    public List<BlockPos> desksOf(ServerLevel level, UUID network) {
        return CreateDesks.in(level, network);
    }

    @Override
    public boolean isSeat(ServerLevel level, BlockPos at) {
        return level.isLoaded(at) && level.getBlockState(at).getBlock() instanceof SeatBlock;
    }

    @Override
    public List<BlockPos> seatsBeside(ServerLevel level, BlockPos at) {
        List<BlockPos> seats = new ArrayList<>();
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos cell = at.relative(side);
            if (isSeat(level, cell)) {
                seats.add(cell.immutable());
            }
        }
        return List.copyOf(seats);
    }

    @Override
    public boolean sit(ServerLevel level, BlockPos seat, Entity body) {
        if (!(level.getBlockState(seat).getBlock() instanceof SeatBlock)
            || SeatBlock.isSeatOccupied(level, seat)) {
            return false;
        }
        SeatBlock.sitDown(level, seat, body);
        return body.isPassenger();
    }

    @Override
    public Optional<UUID> sitting(ServerLevel level, BlockPos seat) {
        for (SeatEntity taken : level.getEntitiesOfClass(SeatEntity.class, new AABB(seat))) {
            for (Entity rider : taken.getPassengers()) {
                return Optional.of(rider.getUUID());
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean busy(MinecraftServer server, UUID network) {
        for (LogisticallyLinkedBehaviour link : LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
            if (link.blockEntity instanceof PackagerLinkBlockEntity packagerLink) {
                PackagerBlockEntity packager = packagerLink.getPackager();
                if (packager != null && packager.isTooBusyFor(LogisticallyLinkedBehaviour.RequestType.RESTOCK)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public OptionalInt requestFrom(ServerLevel level, UUID network, BlockPos source, String address,
                                   ItemStack item, long count) {
        if (count <= 0 || count > Integer.MAX_VALUE || item.isEmpty()) {
            return OptionalInt.empty();
        }
        for (LogisticallyLinkedBehaviour link : LogisticallyLinkedBehaviour.getAllPresent(network, false)) {
            if (!(link.blockEntity instanceof PackagerLinkBlockEntity packagerLink)
                || packagerLink.getLevel() != level || link.redstonePower > 0) {
                continue;
            }
            PackagerBlockEntity packager = packagerLink.getPackager();
            if (packager == null || !level.isLoaded(source)
                || !source.equals(packager.targetInventory.getTarget().getConnectedPos())
                || packager.isTooBusyFor(LogisticallyLinkedBehaviour.RequestType.RESTOCK)) {
                continue;
            }
            IItemHandler inventory = packager.targetInventory.getInventory();
            if (inventory == null || extract(inventory, stack -> ItemStack.isSameItemSameComponents(stack, item),
                count, true).stream().mapToLong(ItemStack::getCount).sum() < count) {
                continue;
            }
            int id = ThreadLocalRandom.current().nextInt();
            var order = PackageOrderWithCrafts.simple(List.of(new BigItemStack(item, (int) count)));
            var request = link.processRequest(item, (int) count, address, 0, new MutableBoolean(true),
                id, order, null);
            if (request == null || request.getFirst() != packager || request.getSecond().getCount() != count) {
                continue;
            }
            LogisticsManager.performPackageRequests(ImmutableListMultimap.of(packager, request.getSecond()));
            InventoryFeedback.store(level, WorldPos.of(level, source));
            return OptionalInt.of(id);
        }
        return OptionalInt.empty();
    }

    static Optional<PackagePortBlockEntity> portAt(ServerLevel level, BlockPos at) {
        if (!level.isLoaded(at)) {
            return Optional.empty();
        }
        return level.getBlockEntity(at) instanceof PackagePortBlockEntity port
            ? Optional.of(port) : Optional.empty();
    }

    private static Parcel parcelOf(ItemStack parcel) {
        return new Parcel(contentsOf(parcel), orderOf(parcel));
    }

    private static OptionalInt orderOf(ItemStack parcel) {
        return PackageItem.hasOrderData(parcel) ? OptionalInt.of(PackageItem.getOrderId(parcel)) : OptionalInt.empty();
    }

    private static List<ItemStack> contentsOf(ItemStack parcel) {
        ItemStackHandler held = PackageItem.getContents(parcel);
        List<ItemStack> contents = new ArrayList<>(held.getSlots());
        for (int slot = 0; slot < held.getSlots(); slot++) {
            ItemStack stack = held.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                contents.add(stack.copy());
            }
        }
        return List.copyOf(contents);
    }
}
