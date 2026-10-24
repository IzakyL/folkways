package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** A Vanilla weapon implementation. Opening transfers an actual item, never a presentation copy. */
public interface WeaponAdapter {
    boolean accepts(ItemStack stack);

    double range();

    default boolean ranged() { return range() > 4.5; }

    /** Inventory-dependent readiness, such as an actual compatible projectile or a loaded chamber. */
    default boolean usable(Worker worker, ItemStack stack) { return accepts(stack); }

    Optional<WeaponSession> open(Worker worker);
}
