package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;

// One committed attempt, delivered after settlement. Stack access always returns defensive copies.
public record StepReceipt(UUID parent, String step, UUID attempt, List<ItemStack> consumed,
                          List<ItemStack> unused, List<ItemStack> made, Optional<RefusalKind> stop) {
    public StepReceipt {
        consumed = copy(consumed);
        unused = copy(unused);
        made = copy(made);
    }

    private static List<ItemStack> copy(List<ItemStack> stacks) { return stacks.stream().map(ItemStack::copy).toList(); }
    @Override public List<ItemStack> consumed() { return copy(consumed); }
    @Override public List<ItemStack> unused() { return copy(unused); }
    @Override public List<ItemStack> made() { return copy(made); }
}
