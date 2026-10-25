package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Delivery;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

// A site's containers stay out of circulation until their contents have been carried away and their blocks go.
final class Emptying {

    private record Held(BlockState state, BlockEntity entity, Optional<UUID> holding) { }
    private record View(IItemHandler items, Optional<Direction> side) { }
    private record Trip(UUID id, WorldPos into, ItemStack stack) { }

    private final Optional<Colony> colony;
    private final BuildSite.Asks asks;
    private final ResourceLocation owner;
    private final Map<WorldPos, Held> held = new LinkedHashMap<>();
    private final Map<WorldPos, Trip> taking = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<UUID> ended = new ConcurrentLinkedQueue<>();
    private ServerLevel level;

    Emptying(UUID order, BuildSite.Asks asks) {
        this.colony = asks.colony();
        this.asks = asks;
        owner = ResourceLocation.fromNamespaceAndPath("folkways", "build/" + order);
    }

    Optional<BuildRefusal> before(ServerLevel level, Map<BlockPos, BlockState> cells) {
        try {
            return read(level, cells);
        } catch (RuntimeException | LinkageError unreadable) {
            return Optional.of(BuildRefusal.UNREADABLE_CONTAINER);
        }
    }

    private Optional<BuildRefusal> read(ServerLevel level, Map<BlockPos, BlockState> cells) {
        this.level = level;
        settle();
        for (BlockPos cell : cells.keySet()) {
            if (!level.isLoaded(cell)) {
                return Optional.of(BuildRefusal.CELL_NOT_LOADED);
            }
            BlockState state = level.getBlockState(cell);
            if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = cell.relative(ChestBlock.getConnectedDirection(state));
                if (!level.isLoaded(other)) {
                    return Optional.of(BuildRefusal.CELL_NOT_LOADED);
                }
                hold(level, other);
            }
            hold(level, cell);
        }
        rule();
        for (BlockPos cell : cells.keySet()) {
            WorldPos at = WorldPos.of(level, Containers.anchor(level, cell));
            Trip trip = taking.get(at);
            if (trip != null) {
                Container store = colony.flatMap(one -> one.store(level, trip.into())).orElse(null);
                return Optional.of(store == null || !StoreRule.stores(colony.get().storeRules(trip.into()))
                    || !fits(store, trip.stack()) ? BuildRefusal.NO_ROOM_TO_EMPTY : BuildRefusal.EMPTYING_CONTAINER);
            }
            List<View> views = views(level, cell);
            boolean full = false;
            for (View view : views) {
                for (int slot = 0; slot < view.items().getSlots(); slot++) {
                    ItemStack stack = view.items().getStackInSlot(slot);
                    if (stack.isEmpty()) {
                        continue;
                    }
                    full = true;
                    // One ordinary stack per carry also fits a resident's smallest pack.
                    ItemStack load = view.items().extractItem(slot, Math.min(stack.getCount(), stack.getMaxStackSize()), true);
                    if (load.isEmpty()) {
                        continue;
                    }
                    Optional<Stances> loading = Stances.of(level, Reach.workableCells(level, cell));
                    if (loading.isEmpty()) {
                        return Optional.of(BuildRefusal.NO_STANCE);
                    }
                    if (colony.isEmpty()) {
                        return Optional.of(BuildRefusal.NO_ROOM_TO_EMPTY);
                    }
                    for (WorldPos into : colony.get().storesIn(level.dimension())) {
                        if (held.containsKey(into) || !into.in(level) || !level.isLoaded(into.block(level))
                            || !StoreRule.stores(colony.get().storeRules(into))) {
                            continue;
                        }
                        Container store = colony.get().store(level, into).orElse(null);
                        if (store == null || !fits(store, load)) {
                            continue;
                        }
                        Optional<Stances> unloading = Stances.of(level, Reach.workableCells(level, into.block(level)));
                        if (unloading.isEmpty()) {
                            continue;
                        }
                        UUID id = UUID.randomUUID();
                        taking.put(at, new Trip(id, into, load.copy()));
                        Delivery.Source source = new Delivery.Source(WorldPos.of(level, cell), loading.get(),
                            view.side(), slot, load, level.getBlockState(cell), level.getBlockEntity(cell));
                        asks.submit(level.dimension(), Grown.of(Delivery.from(id, BuildContent.ID, source, into,
                            unloading.get(), count -> { })), (node, how) -> {
                                if (node.equals(id)) ended.add(id);
                            });
                        return Optional.of(BuildRefusal.EMPTYING_CONTAINER);
                    }
                    return Optional.of(BuildRefusal.NO_ROOM_TO_EMPTY);
                }
            }
            BlockEntity entity = level.getBlockEntity(cell);
            if (full || unreadable(level, cell, entity, views)) {
                return Optional.of(BuildRefusal.UNREADABLE_CONTAINER);
            }
        }
        return Optional.empty();
    }

    static Optional<BuildRefusal> check(ServerLevel level, Map<BlockPos, BlockState> cells) {
        for (BlockPos cell : cells.keySet()) {
            for (View view : views(level, cell)) {
                for (int slot = 0; slot < view.items().getSlots(); slot++) {
                    if (!view.items().getStackInSlot(slot).isEmpty()) {
                        return Optional.of(BuildRefusal.EMPTYING_CONTAINER);
                    }
                }
            }
            if (unreadable(level, cell, level.getBlockEntity(cell), views(level, cell))) {
                return Optional.of(BuildRefusal.UNREADABLE_CONTAINER);
            }
        }
        return Optional.empty();
    }

    private void hold(ServerLevel level, BlockPos cell) {
        WorldPos at = WorldPos.of(level, cell);
        Optional<UUID> holding = colony.flatMap(one -> one.holdingAt(at)).map(one -> one.id());
        if (level.getBlockEntity(cell) != null || holding.isPresent()) {
            held.putIfAbsent(at, new Held(level.getBlockState(cell), level.getBlockEntity(cell), holding));
        }
    }

    private void rule() {
        if (level == null) return;
        colony.ifPresent(one -> {
            one.withhold(owner, level.dimension(), held.keySet());
            one.rule(owner, level.dimension(), held.keySet().stream()
                .<StoreRule>map(at -> new StoreRule.Access(at, false, false)).toList());
        });
    }

    private void settle() {
        for (UUID id; (id = ended.poll()) != null; ) {
            UUID done = id;
            taking.values().removeIf(trip -> trip.id().equals(done));
        }
    }

    // The actual removal releases the holding, through the same path as a block lost outside construction.
    void removed(ServerLevel level) {
        held.entrySet().removeIf(entry -> {
            BlockPos cell = entry.getKey().block(level);
            if (!level.isLoaded(cell) || level.getBlockState(cell).is(entry.getValue().state().getBlock())
                && level.getBlockEntity(cell) == entry.getValue().entity()) {
                return false;
            }
            colony.ifPresent(one -> entry.getValue().holding().ifPresent(holding -> one.release(holding, Release.GONE)));
            return true;
        });
        rule();
    }

    void close() {
        taking.values().forEach(trip -> asks.withdraw(trip.id()));
        taking.clear();
        held.clear();
        rule();
    }

    private static boolean unreadable(ServerLevel level, BlockPos cell, BlockEntity entity, List<View> views) {
        if (entity instanceof Container container) return !container.isEmpty();
        // An inventory screen without exposed slots cannot be proved empty.
        return entity != null && views.isEmpty() && level.getBlockState(cell).getMenuProvider(level, cell) != null;
    }

    private static List<View> views(ServerLevel level, BlockPos cell) {
        IItemHandler items = level.getCapability(Capabilities.ItemHandler.BLOCK, cell, null);
        if (items != null) return List.of(new View(items, Optional.empty()));
        List<View> found = new ArrayList<>();
        for (Direction side : Direction.values()) {
            items = level.getCapability(Capabilities.ItemHandler.BLOCK, cell, side);
            if (items != null) found.add(new View(items, Optional.of(side)));
        }
        return found;
    }

    private static boolean fits(Container store, ItemStack stack) {
        SimpleContainer copy = new SimpleContainer(store.getContainerSize()) {
            @Override public boolean canPlaceItem(int slot, ItemStack goods) {
                return Containers.accepts(store, slot, goods);
            }
            @Override public int getMaxStackSize() { return store.getMaxStackSize(); }
        };
        for (int slot = 0; slot < store.getContainerSize(); slot++) {
            copy.setItem(slot, store.getItem(slot).copy());
        }
        return Containers.insert(copy, stack).isEmpty();
    }
}
