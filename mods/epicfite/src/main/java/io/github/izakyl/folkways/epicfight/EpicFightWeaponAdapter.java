package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponScores;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.equipment.ResidentEquipment;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.neoforged.neoforge.common.Tags;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.item.CapabilityItem;
import yesman.epicfight.world.capabilities.item.CapabilityItem.WeaponCategories;
import yesman.epicfight.world.capabilities.item.RangedWeaponCapability;

/** Borrows the same real main hand used by Vanilla's native sessions. Ranged weapons keep their own adapters. */
public final class EpicFightWeaponAdapter implements WeaponAdapter {
    public static final EpicFightWeaponAdapter INSTANCE = new EpicFightWeaponAdapter();

    private EpicFightWeaponAdapter() { }

    @Override
    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof ProjectileWeaponItem) {
            return false;
        }
        CapabilityItem capability = EpicFightCapabilities.getItemStackCapability(stack);
        if (capability instanceof RangedWeaponCapability || capability.isWeaponCategory(WeaponCategories.RANGED)
                || capability.isWeaponCategory(WeaponCategories.SHIELD)) {
            return false;
        }
        return stack.is(Tags.Items.MELEE_WEAPON_TOOLS) || stack.getItem() instanceof AxeItem
            || !capability.isEmpty() && !capability.isWeaponCategory(WeaponCategories.FIST);
    }

    @Override
    public double range() { return 2.5; }

    @Override
    public boolean ranged() { return false; }

    @Override
    public boolean supportsAssignment() { return true; }

    @Override
    public double sustainedDamagePerSecond(Worker worker, ItemStack actual) {
        if (!(worker.body() instanceof ResidentEntity resident)) return 0;
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
        if (patch == null) return 0;
        // EF AttackAnimation scales its real clip by this native capability/weight-adjusted speed.
        float speed = patch.getModifiedAttackSpeedOfItem(EpicFightCapabilities.getItemStackCapability(actual),
            (float) WeaponScores.attackSpeed(resident, actual));
        return WeaponScores.meleeDamage(resident, actual) * Math.max(0, speed);
    }

    @Override
    public boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack) && worker.body() instanceof ResidentEntity resident
            && !resident.isPassenger() && ResidentEpicFightPatch.of(resident) != null;
    }

    @Override
    public boolean acceptsBarehanded(Worker worker) {
        return worker.body() instanceof ResidentEntity resident && !resident.isPassenger()
            && ResidentEpicFightPatch.of(resident) != null && Weapons.barehanded(worker);
    }

    @Override
    public Optional<WeaponSession> open(Worker worker) {
        ResidentEpicFightPatch patch = readyPatch(worker);
        if (patch == null) { return Optional.empty(); }
        ResidentEntity resident = (ResidentEntity) worker.body();
        var selected = Weapons.selection(worker);
        if (selected.isPresent()) return selected.filter(choice -> choice.adapter() == this)
            .flatMap(choice -> openAssigned(worker, choice.actual()));
        if (ResidentEquipment.mainLocked(resident) || !acceptsBarehanded(worker)) { return Optional.empty(); }
        EpicFightWeaponSession session = new EpicFightWeaponSession(worker, resident, patch, ItemStack.EMPTY);
        resident.claimWeaponHand(session, ItemStack.EMPTY);
        return Optional.of(session);
    }

    @Override
    public Optional<WeaponSession> openAssigned(Worker worker, ItemStack selected) {
        ResidentEpicFightPatch patch = readyPatch(worker);
        if (patch == null || selected.isEmpty()) { return Optional.empty(); }
        ResidentEntity resident = (ResidentEntity) worker.body();
        if (!ResidentEquipment.selectedForCombat(resident, selected)
                || !usable(worker, selected)) { return Optional.empty(); }
        for (int slot = 0; slot < worker.pack().getContainerSize(); slot++) {
            if (worker.pack().getItem(slot) == selected) { return borrow(worker, resident, patch, slot, selected); }
        }
        return Optional.empty();
    }

    private static ResidentEpicFightPatch readyPatch(Worker worker) {
        if (!(worker.body() instanceof ResidentEntity resident) || resident.hasWeaponSession()
                || !worker.resident().id().equals(resident.id()) || worker.pack() != resident.pack()
                || !resident.isAlive() || resident.isRemoved()
                || resident.isPassenger() || !(resident.level() instanceof ServerLevel level)
                || !level.getServer().isSameThread()) {
            return null;
        }
        ResidentEpicFightPatch patch = ResidentEpicFightPatch.of(resident);
        return patch == null || patch.uninitialized() || patch.hasMotion() || patch.blocksLabor() ? null : patch;
    }

    private static Optional<WeaponSession> borrow(Worker worker, ResidentEntity resident,
            ResidentEpicFightPatch patch, int slot, ItemStack selected) {
        if (worker.pack().getItem(slot) != selected) { return Optional.empty(); }
        ItemStack actual = worker.pack().removeItemNoUpdate(slot);
        EpicFightWeaponSession session = new EpicFightWeaponSession(worker, resident, patch, actual);
        try {
            resident.claimWeaponHand(session, actual);
        } catch (RuntimeException | LinkageError failure) {
            if (resident.ownsWeaponHand(session)) {
                session.close();
            } else if (worker.pack().getItem(slot).isEmpty()) {
                worker.pack().setItem(slot, actual);
            } else {
                ItemStack remainder = resident.pack().insert(actual);
                if (!remainder.isEmpty()) { resident.spawnAtLocation(remainder); }
            }
            throw failure;
        }
        return Optional.of(session);
    }
}
