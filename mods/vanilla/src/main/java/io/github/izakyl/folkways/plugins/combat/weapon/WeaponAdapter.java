package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Goods;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

/** A Vanilla weapon implementation. Opening transfers an actual item, never a presentation copy. */
public interface WeaponAdapter {
    boolean accepts(ItemStack stack);

    double range();

    default boolean ranged() { return range() > 4.5; }

    /** Inventory-dependent readiness, such as an actual compatible projectile or a loaded chamber. */
    default boolean usable(Worker worker, ItemStack stack) { return accepts(stack); }

    /** Expected sustained single-target damage per second, not a material/rarity rank.
     * Called only for a real usable candidate during bounded selection. Unknown adapters tie at zero.
     */
    default double sustainedDamagePerSecond(Worker worker, ItemStack actual) { return 0; }

    /** An optional native animation bridge may also execute this body's permitted unarmed attacks. */
    default boolean acceptsBarehanded(Worker worker) { return false; }

    /** Optional ammunition demand, independent of readiness: an empty weapon still needs real stock. */
    default Optional<Ammunition> ammunition(ItemStack actualWeapon, int requestedReserve) { return Optional.empty(); }

    /** Stock is a coarse query; compatible checks the actual components. Loaded counts paid physical units. */
    record Ammunition(ItemSpec stock, Predicate<ItemStack> compatible, int target, int loaded) {
        public Ammunition {
            Objects.requireNonNull(stock); Objects.requireNonNull(compatible);
            if (target < 1 || target > 4096 || loaded < 0) throw new IllegalArgumentException("invalid ammunition accounting");
        }
        public boolean accepts(ItemStack actual) { return Goods.matches(actual, stock) && compatible.test(actual); }
    }

    Optional<WeaponSession> open(Worker worker);

    /** Legacy adapters may rescan the pack. They must opt in before accepting an exact assignment. */
    default boolean supportsAssignment() { return false; }

    /** Transfer this actual selected stack, or decline; never substitute a different carried item. */
    default Optional<WeaponSession> openAssigned(Worker worker, ItemStack selected) { return Optional.empty(); }
}
