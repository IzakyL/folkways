package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.plugins.combat.weapon.LoadoutProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Recognizes native melee weapons in upstream profiles; profiles own quantity and selection. */
public final class EpicFightLoadoutProvider implements LoadoutProvider {
    public static final EpicFightLoadoutProvider INSTANCE = new EpicFightLoadoutProvider();

    private EpicFightLoadoutProvider() { }

    @Override
    public boolean accepts(ItemStack weapon) { return EpicFightWeaponAdapter.INSTANCE.accepts(weapon); }

    @Override
    public boolean ranged(ItemStack weapon) { return false; }

    @Override
    public double range(ItemStack weapon) { return EpicFightWeaponAdapter.INSTANCE.range(); }

    @Override
    public Component label() { return Component.literal("Epic Fight"); }
}
