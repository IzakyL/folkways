package io.github.izakyl.folkways.plugins.combat.weapon;

import java.util.LinkedHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.resources.ResourceKey;

/** Read-only estimates from the actual components. No temporary equipping or mutation of the mob. */
public final class WeaponScores {
    private WeaponScores() { }

    public static double attackSpeed(Mob body, ItemStack actual) {
        return Math.max(.1, attribute(body, actual, Attributes.ATTACK_SPEED, 4));
    }

    public static double meleeDamage(Mob body, ItemStack actual) {
        double damage = attribute(body, actual, Attributes.ATTACK_DAMAGE, 1);
        int sharpness = enchantment(body, actual, Enchantments.SHARPNESS);
        // Target-independent Sharpness is counted; Smite/Bane, sweeping and knockback are not
        // assumed to increase damage against an unknown single target.
        return Math.max(0, damage + (sharpness == 0 ? 0 : .5 * sharpness + .5));
    }

    /** Native hits respect both the real item speed and the target's ordinary ten-tick hurt window. */
    public static int meleeCycleTicks(Mob body, ItemStack actual) {
        return Math.max(10, Math.min(200, (int) Math.ceil(20 / attackSpeed(body, actual))));
    }

    public static double nativeMelee(Mob body, ItemStack actual) {
        return meleeDamage(body, actual) * 20 / meleeCycleTicks(body, actual);
    }

    public static int enchantment(Mob body, ItemStack actual, ResourceKey<Enchantment> key) {
        return body.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).get(key)
            .map(holder -> EnchantmentHelper.getItemEnchantmentLevel(holder, actual)).orElse(0);
    }

    private static double attribute(Mob body, ItemStack actual, Holder<Attribute> attribute, double fallback) {
        var instance = body.getAttribute(attribute);
        double base = instance == null ? fallback : instance.getBaseValue();
        var modifiers = new LinkedHashMap<ResourceLocation, AttributeModifier>();
        if (instance != null) instance.getModifiers().forEach(modifier -> modifiers.put(modifier.id(), modifier));
        // Remove the current real/presentation hand's contribution before applying the candidate.
        // Perks, effects and offhand/armor modifiers remain part of the same resident's estimate.
        body.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) modifiers.remove(modifier.id());
        });
        actual.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) modifiers.put(modifier.id(), modifier);
        });
        double added = base;
        for (var modifier : modifiers.values()) if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) added += modifier.amount();
        double value = added;
        for (var modifier : modifiers.values()) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE) value += added * modifier.amount();
        for (var modifier : modifiers.values()) if (modifier.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL) value *= 1 + modifier.amount();
        return Double.isFinite(value) ? Math.max(0, value) : 0;
    }
}
