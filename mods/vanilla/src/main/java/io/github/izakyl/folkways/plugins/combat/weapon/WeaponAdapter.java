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

    /** An optional native animation bridge may also execute this body's permitted unarmed attacks. */
    default boolean acceptsBarehanded(Worker worker) { return false; }

    Optional<WeaponSession> open(Worker worker);

    /** Legacy adapters may rescan the pack. They must opt in before accepting an exact assignment. */
    default boolean supportsAssignment() { return false; }

    /** Transfer this actual selected stack, or decline; never substitute a different carried item. */
    default Optional<WeaponSession> openAssigned(Worker worker, ItemStack selected) { return Optional.empty(); }
}
