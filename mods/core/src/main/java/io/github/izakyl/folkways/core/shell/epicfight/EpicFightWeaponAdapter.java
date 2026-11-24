package io.github.izakyl.folkways.core.shell.epicfight;

import io.github.izakyl.folkways.core.combat.api.WeaponAdapter;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import io.github.izakyl.folkways.core.shell.epicfight.api.EpicFightCombatant;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.common.Tags;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.HumanoidMobPatch;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.RangedWeaponCapability;

/** Uses the same real main hand used by Vanilla's native attacks. Ranged weapons keep their own adapters. */
public final class EpicFightWeaponAdapter implements WeaponAdapter {
    public static final EpicFightWeaponAdapter INSTANCE = new EpicFightWeaponAdapter();

    private EpicFightWeaponAdapter() { }

    @Override
    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof ProjectileWeaponItem) {
            return false;
        }
        // EF also gives digging tools weapon capabilities; upstream never fights with them, so neither do we.
        if (stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS) || stack.is(ItemTags.HOES)) {
            return false;
        }
        CapabilityItem capability = EpicFightCapabilities.getItemStackCapability(stack);
        if (capability instanceof RangedWeaponCapability || capability.isWeaponCategory(WeaponCategories.RANGED)
                || capability.isWeaponCategory(WeaponCategories.SHIELD)
                || capability.isWeaponCategory(WeaponCategories.PICKAXE)
                || capability.isWeaponCategory(WeaponCategories.SHOVEL)
                || capability.isWeaponCategory(WeaponCategories.HOE)
                || capability.isWeaponCategory(WeaponCategories.NOT_WEAPON)) {
            return false;
        }
        return stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.getItem() instanceof AxeItem
            || !capability.isEmpty() && !capability.isWeaponCategory(WeaponCategories.FIST);
    }

    // Report a conservative native reach so upstream's stance planner actually approaches the foe.
    // Its ordinary 4.5-block melee range would let short EF blades swing outside their colliders.
    @Override
    public double range() {
        return 2.5;
    }

    @Override
    public boolean ranged() {
        return false;
    }

    @Override
    public boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack) && worker.body() instanceof WeaponHands
            && !worker.body().isPassenger() && combatant(worker.body()) != null;
    }

    @Override
    public Optional<WeaponAttack> open(Worker worker) {
        // Upstream Weapons selects the loadout and calls the exact-stack overload.
        return Optional.empty();
    }

    @Override
    public Optional<WeaponAttack> open(Worker worker, ItemStack selected) {
        EpicFightCombatant patch = readyPatch(worker);
        if (patch == null || !patch.mayEngage(worker) || !usable(worker, selected)) {
            return Optional.empty();
        }
        WeaponHands hands = (WeaponHands) worker.body();
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            if (worker.pack().getItem(slot) == selected) {
                return draw(worker, hands, patch, slot, selected);
            }
        }
        return Optional.empty();
    }

    private static EpicFightCombatant combatant(Mob body) {
        return EpicFightCapabilities.getEntityPatch(body, HumanoidMobPatch.class) instanceof EpicFightCombatant patch
            ? patch : null;
    }

    private static EpicFightCombatant readyPatch(Worker worker) {
        if (!(worker.body() instanceof WeaponHands hands) || (hands.hasActiveAttack() || hands.weaponDrawn())
                || !worker.resident().id().equals(hands.id()) || worker.pack() != hands.pack()
                || !worker.body().isAlive() || worker.body().isRemoved()
                || worker.body().isPassenger() || !(worker.body().level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) {
            return null;
        }
        EpicFightCombatant patch = combatant(worker.body());
        return patch == null || patch.uninitialized() || patch.blocksLabor() ? null : patch;
    }

    private static Optional<WeaponAttack> draw(Worker worker, WeaponHands hands,
            EpicFightCombatant patch, int slot, ItemStack selected) {
        if (worker.pack().getItem(slot) != selected) {
            return Optional.empty();
        }
        // Resolve external services before changing equipment.
        EpicFightWeaponAttack attack = new EpicFightWeaponAttack(worker, hands, patch);
        if (!hands.drawWeapon(slot)) {
            return Optional.empty();
        }
        try {
            hands.beginAttack(attack);
        } catch (RuntimeException | LinkageError failure) {
            attack.stop();
            hands.holsterWeapon();
            throw failure;
        }
        return Optional.of(attack);
    }
}
