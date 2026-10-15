package io.github.izakyl.folkways.plugins.cookery.stockpot;

import java.util.ArrayList;
import java.util.function.Predicate;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

// Matches all slots before anything is put in the pot. Broad tags must not consume a later slot's only choice.
record StockpotSupplies(List<ItemStack> slots, List<ItemStack> unused) {

    static Optional<StockpotSupplies> draw(List<ItemStack> supplied, List<Predicate<ItemStack>> checks) {
        List<ItemStack> pool = supplied.stream().map(ItemStack::copy).toList();
        List<ItemStack> slots = new ArrayList<>();
        checks.forEach(ignored -> slots.add(ItemStack.EMPTY));
        if (!match(pool, checks, slots, 0)) {
            return Optional.empty();
        }
        return Optional.of(new StockpotSupplies(List.copyOf(slots),
            pool.stream().filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList()));
    }

    private static boolean match(List<ItemStack> pool, List<Predicate<ItemStack>> checks,
                                 List<ItemStack> slots, int placed) {
        if (placed == checks.size()) {
            return true;
        }
        int next = -1;
        int fewest = Integer.MAX_VALUE;
        for (int index = 0; index < checks.size(); index++) {
            if (!slots.get(index).isEmpty()) {
                continue;
            }
            int choices = 0;
            for (ItemStack stack : pool) {
                if (!stack.isEmpty() && checks.get(index).test(stack)) {
                    choices++;
                }
            }
            if (choices < fewest) {
                next = index;
                fewest = choices;
            }
        }
        if (next < 0 || fewest == 0) {
            return false;
        }
        for (ItemStack stack : pool) {
            if (stack.isEmpty() || !checks.get(next).test(stack)) {
                continue;
            }
            ItemStack taken = stack.split(1);
            slots.set(next, taken);
            if (match(pool, checks, slots, placed + 1)) {
                return true;
            }
            slots.set(next, ItemStack.EMPTY);
            stack.grow(1);
        }
        return false;
    }
}
