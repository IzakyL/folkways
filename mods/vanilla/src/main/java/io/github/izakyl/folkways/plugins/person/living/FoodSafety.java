package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SuspiciousStewEffects;

final class FoodSafety {
    // Some foods hurt directly in finishUsingItem rather than declaring a status effect.
    private static final TagKey<Item> UNSAFE = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("folkways", "unsafe_food"));

    private FoodSafety() {
    }

    // Supply plans name item kinds; the actual stack is checked again in the pack and before consumption.
    static Optional<ItemSpec> allowed(ItemSpec configured) {
        List<ItemSpec> allowed = new ArrayList<>();
        for (Item item : Goods.members(configured)) {
            ItemStack stack = item.getDefaultInstance();
            FoodProperties food = stack.getFoodProperties(null);
            if (safe(stack, food) && food.nutrition() > 0) {
                allowed.add(Goods.specOf(stack));
            }
        }
        return allowed.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(allowed));
    }

    static boolean safe(ItemStack stack, FoodProperties food) {
        if (food == null || stack.is(UNSAFE)) {
            return false;
        }
        if (food.effects().stream().anyMatch(effect -> effect.probability() > 0.0F
                && effect.effect().getEffect().value().getCategory() == MobEffectCategory.HARMFUL)) {
            return false;
        }
        return stack.getOrDefault(DataComponents.SUSPICIOUS_STEW_EFFECTS, SuspiciousStewEffects.EMPTY)
            .effects().stream().noneMatch(effect -> effect.effect().value().getCategory() == MobEffectCategory.HARMFUL);
    }
}
