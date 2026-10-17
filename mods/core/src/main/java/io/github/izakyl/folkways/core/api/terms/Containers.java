package io.github.izakyl.folkways.core.api.terms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class Containers {

    private static volatile List<ItemStack> samples;

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

    public static Optional<Container> at(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chest) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                && !level.isLoaded(pos.relative(ChestBlock.getConnectedDirection(state)))) {
                return Optional.empty();
            }
            return Optional.ofNullable(ChestBlock.getContainer(chest, state, level, pos, true));
        }
        BlockEntity entity = level.getBlockEntity(pos);
        return entity instanceof Container container ? Optional.of(container) : Optional.empty();
    }

    public static boolean gone(Level level, BlockPos pos) {
        return Loaded.around(level, pos, 1) && at(level, pos).isEmpty();
    }

    public static BlockPos anchor(Level level, BlockPos clicked) {
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
        if (!PICKY.get(container.getClass())) {
            return Set.of();
        }
        Set<Item> refused = new HashSet<>();
        for (ItemStack sample : samples()) {
            boolean taken = false;
            for (int slot = 0; slot < container.getContainerSize() && !taken; slot++) {
                taken = accepts(container, slot, sample);
            }
            if (!taken) {
                refused.add(sample.getItem());
            }
        }
        return Set.copyOf(refused);
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
        return Math.min(into.getMaxStackSize(), stack.getMaxStackSize());
    }
}
