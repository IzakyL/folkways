package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.resident.ResidentKinds;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.ItemAbility;

public final class Tools {

    private Tools() {
    }

    public static boolean answers(ItemStack stack, ToolNeed need) {
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
