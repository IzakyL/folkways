package io.github.izakyl.folkways.core.item.api;

import io.github.izakyl.folkways.core.world.api.Loaded;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class Containers {

    private static volatile List<ItemStack> samples;
    private static volatile List<Adapter<?>> adapters = List.of();
    private static BiFunction<Level, BlockPos, BlockPos> anchors = Containers::chestAnchor;
    private static java.util.function.BiPredicate<Level, BlockPos> stores = (level, pos) -> at(level, pos).isPresent();

    private record Adapter<T extends BlockEntity>(Class<T> type,
            BiFunction<Level, T, Optional<Container>> read) {
        Optional<Container> at(Level level, BlockEntity entity) {
            return type.isInstance(entity) ? read.apply(level, type.cast(entity)) : Optional.empty();
        }
    }

    // Optional integrations expose their real slots without making the core depend on their classes.
    public static synchronized <T extends BlockEntity> void register(Class<T> type,
            BiFunction<Level, T, Optional<Container>> read) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(read, "read");
        if (adapters.stream().anyMatch(adapter -> adapter.type().equals(type))) {
            throw new IllegalArgumentException("container adapter already registered: " + type);
        }
        List<Adapter<?>> next = new ArrayList<>(adapters);
        next.add(new Adapter<>(type, read));
        adapters = List.copyOf(next);
    }

    // A container that never overrides canPlaceItem and has no faces takes anything, and needs no reading.
    private static final ClassValue<Boolean> PICKY = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> kind) {
            try {
                return WorldlyContainer.class.isAssignableFrom(kind)
                    || kind.getMethod("canPlaceItem", int.class, ItemStack.class).getDeclaringClass()
                    != Container.class;
            } catch (NoSuchMethodException unexpected) {
                return true;
            }
        }
    };

    private Containers() {
    }

    public interface Parts {
        Container folkways$first();

        Container folkways$second();
    }

    public interface Contents extends Container {
        boolean known();
    }

    public static boolean known(Container container) {
        if (container instanceof Contents contents) {
            return contents.known();
        }
        if (container instanceof RandomizableContainer loot && loot.getLootTable() != null) {
            return false;
        }
        if (container instanceof CompoundContainer) {
            var halves = (Parts) container;
            return known(halves.folkways$first()) && known(halves.folkways$second());
        }
        return true;
    }

    public static Optional<Container> at(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        BlockEntity entity = blockEntity(level, pos).orElse(null);
        if (state.getBlock() instanceof ChestBlock && entity instanceof ChestBlockEntity chest) {
            if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
                return Optional.of(chest);
            }
            BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
            if (!level.isLoaded(other)) {
                return Optional.empty();
            }
            BlockState adjacent = level.getBlockState(other);
            if (adjacent.getBlock() == state.getBlock()
                    && adjacent.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && adjacent.getValue(ChestBlock.TYPE) != state.getValue(ChestBlock.TYPE)
                    && adjacent.getValue(ChestBlock.FACING) == state.getValue(ChestBlock.FACING)
                    && blockEntity(level, other).orElse(null) instanceof ChestBlockEntity partner) {
                return Optional.of(state.getValue(ChestBlock.TYPE) == ChestType.RIGHT
                    ? new CompoundContainer(chest, partner) : new CompoundContainer(partner, chest));
            }
            return Optional.of(chest);
        }
        if (entity instanceof Container container) {
            return Optional.of(container);
        }
        if (entity != null && !entity.isRemoved()) {
            for (Adapter<?> adapter : adapters) {
                Optional<Container> found = adapter.at(level, entity);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockEntity> blockEntity(Level level, BlockPos at) {
        if (!level.isLoaded(at)) {
            return Optional.empty();
        }
        if (level instanceof ServerLevel server) {
            var chunk = server.getChunkSource().getChunkNow(at.getX() >> 4, at.getZ() >> 4);
            return chunk == null ? Optional.empty() : Optional.ofNullable(chunk.getBlockEntities().get(at))
                .filter(entity -> !entity.isRemoved());
        }
        return Optional.ofNullable(level.getBlockEntity(at));
    }

    public static boolean gone(Level level, BlockPos pos) {
        return Loaded.around(level, pos, 1) && !stores.test(level, pos);
    }

    // Core installs physical store grouping without coupling the item helpers to colony inventory.
    public static void installPorts(BiFunction<Level, BlockPos, BlockPos> anchor,
                                    java.util.function.BiPredicate<Level, BlockPos> present) {
        anchors = Objects.requireNonNull(anchor);
        stores = Objects.requireNonNull(present);
    }

    public static BlockPos anchor(Level level, BlockPos clicked) {
        if (!level.isLoaded(clicked)) {
            return clicked.immutable();
        }
        return anchors.apply(level, clicked);
    }

    public static BlockPos chestAnchor(Level level, BlockPos clicked) {
        if (!level.isLoaded(clicked)) {
            return clicked.immutable();
        }
        BlockState state = level.getBlockState(clicked);
        if (!(state.getBlock() instanceof ChestBlock)
            || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return clicked.immutable();
        }
        BlockPos other = clicked.relative(ChestBlock.getConnectedDirection(state));
        return (clicked.compareTo(other) <= 0 ? clicked : other).immutable();
    }

    public static ItemStack extract(Container from, Predicate<ItemStack> which, int max) {
        ItemStack taken = ItemStack.EMPTY;
        for (int slot = 0; slot < from.getContainerSize() && taken.getCount() < max; slot++) {
            ItemStack stack = from.getItem(slot);
            if (stack.isEmpty() || !which.test(stack)) {
                continue;
            }
            if (!taken.isEmpty() && !ItemStack.isSameItemSameComponents(taken, stack)) {
                continue;
            }
            ItemStack moved = from.removeItem(slot, Math.min(max - taken.getCount(), stack.getCount()));
            if (moved.isEmpty()) {
                continue;
            }
            if (taken.isEmpty()) {
                taken = moved;
            } else {
                taken.grow(moved.getCount());
            }
        }
        if (!taken.isEmpty()) {
            from.setChanged();
        }
        return taken;
    }

    public static ItemStack insert(Container into, ItemStack stack) {
        if (into instanceof Inserting inserting) {
            return inserting.insert(stack);
        }
        ItemStack left = stack.copy();
        int cap = capacity(into, stack);
        for (int slot = 0; slot < into.getContainerSize() && !left.isEmpty(); slot++) {
            ItemStack there = into.getItem(slot);
            if (there.isEmpty() || !ItemStack.isSameItemSameComponents(there, left)
                || !accepts(into, slot, left)) {
                continue;
            }
            int room = cap - there.getCount();
            if (room <= 0) {
                continue;
            }
            int moved = Math.min(room, left.getCount());
            there.grow(moved);
            into.setItem(slot, there);
            left.shrink(moved);
        }
        for (int slot = 0; slot < into.getContainerSize() && !left.isEmpty(); slot++) {
            if (!into.getItem(slot).isEmpty() || !accepts(into, slot, left)) {
                continue;
            }
            into.setItem(slot, left.split(Math.min(cap, left.getCount())));
        }
        if (left.getCount() != stack.getCount()) {
            into.setChanged();
        }
        return left;
    }

    // Whether a resident may put the stack in that slot by hand. A container with faces must also take it through
    // at least one of them: a shulker box keeps other shulker boxes out only there.
    public static boolean accepts(Container into, int slot, ItemStack stack) {
        if (!into.canPlaceItem(slot, stack)) {
            return false;
        }
        if (!(into instanceof WorldlyContainer sided)) {
            return true;
        }
        if (sided.canPlaceItemThroughFace(slot, stack, null)) {
            return true;
        }
        for (Direction face : Direction.values()) {
            if (sided.canPlaceItemThroughFace(slot, stack, face)) {
                return true;
            }
        }
        return false;
    }

    // The items no slot of the container will take, however empty it is. Most take everything, and a shulker box
    // refuses other shulker boxes; a slot that only refuses some goods some of the time is not read here.
    public static Set<Item> refused(Container container) {
        Refusals reading = new Refusals(container);
        while (!reading.step(container, 64)) {
        }
        return reading.items();
    }

    public static final class Refusals {

        private final Set<Item> refused = new HashSet<>();
        private final boolean picky;
        private int sample;
        private int slot;

        public Refusals(Container container) {
            picky = PICKY.get(container.getClass());
        }

        // Each step bounds admission calls, including containers that refuse every sample in every slot.
        public boolean step(Container container, int limit) {
            if (!picky) {
                return true;
            }
            List<ItemStack> all = samples();
            while (sample < all.size() && limit-- > 0) {
                ItemStack stack = all.get(sample);
                if (slot < container.getContainerSize() && accepts(container, slot, stack)) {
                    sample++;
                    slot = 0;
                } else if (++slot >= container.getContainerSize()) {
                    refused.add(stack.getItem());
                    sample++;
                    slot = 0;
                }
            }
            return sample >= all.size();
        }

        public Set<Item> items() {
            return Set.copyOf(refused);
        }
    }

    private static List<ItemStack> samples() {
        List<ItemStack> known = samples;
        if (known == null) {
            List<ItemStack> found = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ItemStack sample = item.getDefaultInstance();
                if (!sample.isEmpty()) {
                    found.add(sample);
                }
            }
            known = List.copyOf(found);
            samples = known;
        }
        return known;
    }

    private static int capacity(Container into, ItemStack stack) {
        return into.getMaxStackSize(stack);
    }
}
