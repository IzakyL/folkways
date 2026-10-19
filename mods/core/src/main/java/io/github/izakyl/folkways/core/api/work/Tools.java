package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbility;

public final class Tools {

    private static volatile Map<ToolNeed, ItemSpec> suppliedTools =
        Map.of();

    public static synchronized void shared(ToolNeed need, ItemSpec goods) {
        Map<ToolNeed, ItemSpec> next =
            new LinkedHashMap<>(suppliedTools);
        next.put(Objects.requireNonNull(need), Objects.requireNonNull(goods));
        suppliedTools = Map.copyOf(next);
    }

    public static Optional<ItemSpec> shared(ToolNeed need) {
        return Optional.ofNullable(suppliedTools.get(need));
    }

    public static List<ItemSpec> sharedGoods() {
        return List.copyOf(new LinkedHashSet<>(suppliedTools.values()));
    }

    // A task-owned tool is reserved for its declared family, even if it also inherits a generic shovel or sword tag.
    public static boolean sharedItem(ItemStack stack) {
        return !stack.isEmpty() && suppliedTools.values().stream()
            .anyMatch(goods -> io.github.izakyl.folkways.core.api.terms.Goods.matches(stack, goods));
    }

    // Planning may schedule a missing shared tool only when its physical delivery is a declared prerequisite.
    public static boolean canPrepare(ResourceLocation kind, NodeSpec spec, List<ItemStack> held) {
        if (ResidentKinds.barehanded(kind, spec.vocation())) {
            return true;
        }
        for (ToolUse use : spec.tools()) {
            if (from(held, use.need()).isPresent()) {
                continue;
            }
            var goods = suppliedTools.get(use.need());
            if (goods == null || spec.needs().stream().noneMatch(need ->
                    need.carried() && need.count() > 0 && need.spec().equals(goods))) {
                return false;
            }
        }
        return true;
    }

    private Tools() {
    }

    public static boolean answers(ItemStack stack, ToolNeed need) {
        if (!(need instanceof ToolNeed.None) && sharedItem(stack) && shared(need)
                .filter(goods -> io.github.izakyl.folkways.core.api.terms.Goods.matches(stack, goods)).isEmpty()) {
            return false;
        }
        return switch (need) {
            case ToolNeed.None ignored -> true;
            case ToolNeed.Ability(ItemAbility ability) -> !stack.isEmpty() && stack.canPerformAction(ability);
            case ToolNeed.SuitableFor(var state) -> !stack.isEmpty() && stack.isCorrectToolForDrops(state);
            case ToolNeed.Tagged(var tag) -> !stack.isEmpty() && stack.is(tag);
        };
    }

    // Whether what is held answers every tool the work asks for, or this kind works its trade barehanded.
    public static boolean equipped(ResourceLocation kind, NodeSpec spec, List<ItemStack> held) {
        if (ResidentKinds.barehanded(kind, spec.vocation())) {
            return true;
        }
        for (ToolUse use : spec.tools()) {
            if (from(held, use.need()).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public static Optional<ItemStack> from(List<ItemStack> held, ToolNeed need) {
        if (need instanceof ToolNeed.None) {
            return Optional.of(ItemStack.EMPTY);
        }
        for (ItemStack stack : held) {
            if (answers(stack, need)) {
                return Optional.of(stack);
            }
        }
        return Optional.empty();
    }
}
