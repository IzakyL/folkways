package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/** A commit-local view of its inputs, including the mapping needed to return unused real goods. */
public record InputAllocation(List<InputReceipt> receipts) {
    public InputAllocation { receipts = List.copyOf(receipts); }

    public List<ItemStack> supplied() {
        return receipts.stream().map(InputReceipt::requested).toList();
    }

    public static boolean canSupply(Container held, List<Need> needs) {
        var pool = new SimpleContainer(held.getContainerSize());
        for (int slot = 0; slot < held.getContainerSize(); slot++) pool.setItem(slot, held.getItem(slot).copy());
        InputSubstitutions.Snapshot substitutions = InputSubstitutions.snapshot();
        for (Need need : needs) {
            long left = need.count();
            List<InputSubstitutions.Choice> choices = new ArrayList<>();
            choices.add(new InputSubstitutions.Choice(need.spec(), need.spec(), 1));
            choices.addAll(substitutions.choices(need));
            for (var choice : choices) {
                if (left <= 0) break;
                Optional<InputAllocation> drawn = draw(pool, stack -> Goods.matches(stack, choice.payment()),
                    left, choice, stack -> { });
                if (drawn.isEmpty()) return false;
                left -= drawn.get().receipts().stream().mapToLong(input -> input.requested().getCount()).sum();
            }
            if (left > 0) return false;
        }
        return true;
    }

    // One payment unit belongs to one complete item identity; an undersized identity cannot hide another.
    public static Optional<InputAllocation> draw(Container from, Predicate<ItemStack> matching, long count,
            InputSubstitutions.Choice choice, Consumer<ItemStack> removed) {
        Map<ItemSpec, Long> held = new LinkedHashMap<>();
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            ItemStack stack = from.getItem(slot);
            if (!stack.isEmpty() && matching.test(stack)) {
                held.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum);
            }
        }
        List<InputReceipt> receipts = new ArrayList<>();
        long left = count;
        for (var entry : held.entrySet()) {
            while (left > 0 && entry.getValue() >= choice.cost()) {
                int units = (int) Math.min(Math.min(left, entry.getValue() / choice.cost()),
                    Integer.MAX_VALUE / choice.cost());
                int amount = units * choice.cost();
                ItemStack taken = Containers.extract(from, stack -> matching.test(stack)
                    && Goods.matches(stack, entry.getKey()), amount, removed);
                // A source changed since it was read: the caller restores every removal, including this one.
                if (taken.getCount() != amount || !Goods.matches(taken, entry.getKey())) return Optional.empty();
                ItemStack target = choice.target().equals(choice.payment()) ? taken.copyWithCount(units)
                    : Goods.display(choice.target()).copyWithCount(units);
                receipts.add(new InputReceipt(target, taken));
                entry.setValue(entry.getValue() - amount);
                left -= units;
            }
        }
        return Optional.of(new InputAllocation(receipts));
    }

    public record Settlement(List<ItemStack> returned, List<InputReceipt> consumed) { }

    public Settlement settle(List<ItemStack> unused) {
        List<ItemStack> back = unused.stream().map(ItemStack::copy).toList();
        List<ItemStack> returned = new ArrayList<>();
        List<InputReceipt> consumed = new ArrayList<>();
        // Work consumes the preferred originals first, so unused alternatives are returned first.
        for (InputReceipt receipt : receipts.reversed()) {
            ItemStack target = receipt.requested();
            int left = target.getCount();
            for (ItemStack stack : back) {
                if (!ItemStack.isSameItemSameComponents(stack, target)) continue;
                int take = Math.min(left, stack.getCount());
                if (take > 0) {
                    returned.add(receipt.paid().copyWithCount(take * receipt.cost()));
                    stack.shrink(take);
                    left -= take;
                }
            }
            if (left > 0) consumed.add(new InputReceipt(target.copyWithCount(left),
                receipt.paid().copyWithCount(left * receipt.cost())));
        }
        if (back.stream().anyMatch(stack -> !stack.isEmpty())) {
            throw new IllegalArgumentException("work returned goods outside its input allocation");
        }
        return new Settlement(List.copyOf(returned), List.copyOf(consumed));
    }
}
