package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.facility.api.control.ItemPort;
import io.github.izakyl.folkways.core.facility.api.control.Pull;
import io.github.izakyl.folkways.core.facility.api.control.SlotWindow;
import io.github.izakyl.folkways.core.facility.api.control.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Applies colony transfer policy before handing a physical request to an item port. */
public final class StoreTransfers {

    private StoreTransfers() {
    }

    public static Optional<SlotWindow> window(List<StoreRule> rules) {
        int from = 0;
        int to = Integer.MAX_VALUE;
        boolean bounded = false;
        for (StoreRule rule : rules) {
            if (rule instanceof StoreRule.Slots slots) {
                bounded = true;
                from = Math.max(from, slots.from());
                to = Math.min(to, slots.to());
            }
        }
        return bounded ? Optional.of(new SlotWindow(from, to)) : Optional.empty();
    }

    public static TransferResult take(ItemPort port, ServerLevel level, List<Pull> requested,
                                      List<StoreRule> rules, boolean simulate) {
        return take(port, level, requested, rules, StoreAccess.localRules(level, port.anchor()), simulate);
    }

    public static TransferResult take(ItemPort port, ServerLevel level, List<Pull> requested,
                                      List<StoreRule> rules, boolean filterTake, boolean simulate) {
        if (!StoreAccess.usability(level, port).usable()) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!StoreRule.takes(rules) || filterTake && requested.stream().anyMatch(pull -> !admits(rules, pull))) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        return port.take(level, requested, window(rules), simulate);
    }

    public static TransferResult put(ItemPort port, ServerLevel level, List<ItemStack> goods,
                                     List<StoreRule> rules, boolean directed, boolean simulate) {
        if (!StoreAccess.usability(level, port).usable()) {
            return new TransferResult.Refused(TransferResult.Why.UNUSABLE);
        }
        if (!directed && !StoreRule.stores(rules)) {
            return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
        }
        if (!directed && goods.stream().anyMatch(stack -> !StoreRule.admits(rules, stack))) {
            return new TransferResult.Refused(port instanceof SlotPort
                ? TransferResult.Why.NO_ROOM : TransferResult.Why.NOT_ALLOWED);
        }
        return port.put(level, goods, window(rules), simulate);
    }

    private static boolean admits(List<StoreRule> rules, Pull pull) {
        return switch (pull) {
            case Pull.Exact exact -> StoreRule.admits(rules, exact.stack());
            case Pull.Matching matching -> StoreRule.admits(rules, matching.spec());
        };
    }
}
