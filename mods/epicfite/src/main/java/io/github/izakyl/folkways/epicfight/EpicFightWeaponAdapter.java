package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.plugins.combat.weapon.WeaponSession;
import io.github.izakyl.folkways.plugins.combat.weapon.Weapons;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
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

    // Report a conservative native reach so upstream's stance planner actually approaches the foe.
    // Its ordinary 4.5-block melee range would let short EF blades swing outside their colliders.
    @Override
    public double range() { return 2.5; }

    @Override
    public boolean ranged() { return false; }

    @Override
    public boolean usable(Worker worker, ItemStack stack) {
        return accepts(stack) && worker.body() instanceof ResidentEntity resident
            && !resident.isPassenger() && ResidentEpicFightPatch.of(resident) != null;
    }

    @Override
    public Optional<WeaponSession> open(Worker worker) {
        // Upstream Weapons selects the loadout and calls the exact-stack overload.
        return Optional.empty();
    }

    @Override
    public Optional<WeaponSession> open(Worker worker, ItemStack selected) {
        ResidentEpicFightPatch patch = readyPatch(worker);
        if (patch == null || !Weapons.permitted(worker) || !usable(worker, selected)) {
            return Optional.empty();
        }
        ResidentEntity resident = (ResidentEntity) worker.body();
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
        return patch == null || patch.uninitialized() || patch.blocksLabor() ? null : patch;
    }

    private static Optional<WeaponSession> borrow(Worker worker, ResidentEntity resident,
            ResidentEpicFightPatch patch, int slot, ItemStack selected) {
        if (worker.pack().getItem(slot) != selected) { return Optional.empty(); }
        // Snapshot external services before removing the item: a failing service must not lose a loan.
        EpicFightWeaponSession session = new EpicFightWeaponSession(worker, resident, patch, selected);
        ItemStack actual = worker.pack().removeItemNoUpdate(slot);
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
