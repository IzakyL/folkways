package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.front.api.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
    static Optional<ItemSpec> allowed(Optional<ItemSpec> configured, boolean blacklist) {
        List<ItemSpec> allowed = new ArrayList<>();
        for (Item item : blacklist ? BuiltInRegistries.ITEM : configured.map(Goods::members).orElse(Set.of())) {
            ItemStack stack = item.getDefaultInstance();
            FoodProperties food = stack.getFoodProperties(null);
            if (blacklist && configured.filter(spec -> Goods.matches(stack, spec)).isPresent()) {
                continue;
            }
            if (safe(stack, food) && food.nutrition() > 0) {
                allowed.add(Goods.specOf(stack));
            }
        }
        return allowed.isEmpty() ? Optional.empty() : Optional.of(ItemSpec.anyOf(allowed));
    }

    static boolean matches(Settings settings, ItemStack stack) {
        boolean listed = settings.items(LivingContent.FOOD.key()).filter(spec -> Goods.matches(stack, spec)).isPresent();
        return settings.choice(LivingContent.FOOD_MODE.key()).equals(LivingContent.BLACKLIST) != listed;
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
