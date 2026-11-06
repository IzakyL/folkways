package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.List;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Describes use of an actual weapon. Profiles, rather than providers, own carried quantities. */
public interface LoadoutProvider {
    boolean accepts(ItemStack weapon);

    default boolean usable(Worker worker, ItemStack weapon) {
        if (!accepts(weapon)) {
            return false;
        }
        return !loadedAmmunition(weapon).isEmpty()
            || ammunition(weapon)
            .map(ammo -> roundsLoaded(weapon, ammo) > 0 || Goods.countIn(worker.pack(), ammo) > 0)
            .orElse(true);
    }


    /** Component-bearing variants offered by the profile weapon picker. */
    default List<ItemStack> weaponChoices() {
        return List.of();
    }

    /** Equipment identity for a choice offered by weaponChoices; mutable ammunition state should not be pinned. */
    default ItemSpec profileWeapon(ItemStack weapon) {
        return ItemSpec.of(weapon.getItem());
    }

    boolean ranged(ItemStack weapon);

    double range(ItemStack weapon);

    /** Shared-fire adapters must support WeaponSession.spottedTarget and validate its live permission at discharge. */
    default boolean indirect(ItemStack weapon) {
        return false;
    }

    /** Opt in to shared direct engagement within range and own sight line, without authorizing pursuit. */
    default boolean longRange(ItemStack weapon) {
        return false;
    }

    default Optional<ItemSpec> ammunition(ItemStack weapon) {
        return Optional.empty();
    }

    default int magazineSize(ItemStack weapon) {
        return 1;
    }

    default int roundsLoaded(ItemStack weapon, ItemSpec ammunition) {
        return 0;
    }

    default List<ItemStack> loadedAmmunition(ItemStack weapon) {
        return ammunition(weapon)
            .map(
            ammo -> {
                int count = roundsLoaded(weapon, ammo);
                return count <= 0
                    ? List.<ItemStack>of()
                    : List.of(Goods.display(ammo).copyWithCount(count));
            })
            .orElse(List.of());
    }

    default int cycleTicks(ItemStack weapon) {
        return 20;
    }

    default int suggestedAmount(ItemStack weapon) {
        return Math.clamp((long) magazineSize(weapon) * Math.max(1, 160 / Math.max(1, cycleTicks(weapon))), 1, 4096);
    }

    default Component label() {
        return Component.empty();
    }
}
