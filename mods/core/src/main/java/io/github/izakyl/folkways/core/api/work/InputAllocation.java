package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import java.util.ArrayList;
import java.util.List;
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
        for (Need need : needs) {
            long left = need.count();
            List<InputSubstitutions.Choice> choices = new ArrayList<>();
            choices.add(new InputSubstitutions.Choice(need.spec(), need.spec(), 1));
            choices.addAll(InputSubstitutions.choices(need));
            for (var choice : choices) {
                while (left > 0) {
                    ItemStack taken = Containers.extract(pool,
                        stack -> Goods.matches(stack, choice.payment()),
                        (int) Math.min(left, Integer.MAX_VALUE / choice.cost()) * choice.cost());
                    if (taken.isEmpty()) break;
                    int units = taken.getCount() / choice.cost();
                    int remainder = taken.getCount() % choice.cost();
                    if (remainder > 0) Containers.insert(pool, taken.copyWithCount(remainder));
                    if (units == 0) break;
                    left -= units;
                }
            }
            if (left > 0) return false;
        }
        return true;
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
