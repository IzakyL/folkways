package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;

/** Capacity backed by a sided item handler at a network attachment's target. */
public final class ExternalRoom {

    private final Map<WorldPos, Map<ItemSpec, Long>> targets = new LinkedHashMap<>();

    public void add(ServerLevel level, BlockPos target, Direction side,
                    ToLongBiFunction<ItemStack, Long> simulate) {
        if (!level.isLoaded(target)) {
            return;
        }
        var handler = level.getCapability(Capabilities.ItemHandler.BLOCK, target, side);
        if (handler == null) {
            return;
        }
        // Slot geometry bounds native simulations, including providers that acknowledge excess items.
        long free = 0;
        Map<ItemSpec, ItemStack> candidates = new LinkedHashMap<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            int limit = handler.getSlotLimit(slot);
            if (!stack.isEmpty()) {
                limit = Math.min(limit, stack.getMaxStackSize());
                candidates.put(Goods.specOf(stack), stack.copyWithCount(1));
            }
            free += Math.max(0L, (long) limit - stack.getCount());
        }
        if (free == 0) {
            return;
        }
        for (var item : BuiltInRegistries.ITEM) {
            ItemStack stack = item.getDefaultInstance();
            if (!stack.isEmpty()) {
                candidates.putIfAbsent(Goods.specOf(stack), stack);
            }
        }
        WorldPos anchor = WorldPos.of(level, Containers.anchor(level, target));
        Map<ItemSpec, Long> limits = targets.computeIfAbsent(anchor, ignored -> new LinkedHashMap<>());
        for (var entry : candidates.entrySet()) {
            long accepted = Math.min(free, simulate.applyAsLong(entry.getValue(), free));
            if (accepted > 0) {
                limits.merge(entry.getKey(), accepted, Math::max);
            }
        }
    }

    public long newKindRoom(Predicate<ItemSpec> isNew) {
        // Simulations are independent. They establish that one new kind fits, even when several
        // candidates compete for the same filtered slot or several targets share the same inventory.
        return targets.values().stream().anyMatch(target -> target.keySet().stream().anyMatch(isNew)) ? 1 : 0;
    }

    public long mergeInto(Map<ItemSpec, Long> limits) {
        long items = 0;
        for (var target : targets.values()) {
            // A homogeneous fill is a conservative shared budget, even with partitioned or partially filled slots.
            long largest = 0;
            for (var entry : target.entrySet()) {
                limits.merge(entry.getKey(), entry.getValue(), ExternalRoom::add);
                largest = Math.max(largest, entry.getValue());
            }
            items = add(items, largest);
        }
        return items;
    }

    public static long add(long one, long other) {
        return other > Long.MAX_VALUE - one ? Long.MAX_VALUE : one + other;
    }
}
