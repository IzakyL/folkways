package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** A Vanilla weapon implementation. Opening transfers an actual item, never a presentation copy. */
public interface WeaponAdapter {
    boolean accepts(ItemStack stack);

    double range();

    default double range(Worker worker, ItemStack weapon) {
        return range();
    }

    default Optional<WeaponAttack> open(Worker worker, ItemStack weapon) {
        return open(worker);
    }

    default boolean ranged() {
        return range() > 4.5;
    }

    /** Inventory-dependent readiness, such as an actual compatible projectile or a loaded chamber. */
    default boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack);
    }


    Optional<WeaponAttack> open(Worker worker);
}
