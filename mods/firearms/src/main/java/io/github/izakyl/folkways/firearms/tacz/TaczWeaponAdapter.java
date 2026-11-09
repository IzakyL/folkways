package io.github.izakyl.folkways.firearms.tacz;

import com.tacz.guns.api.item.IAmmo;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.combat.weapon.WeaponAdapter;
import io.github.izakyl.folkways.core.combat.api.WeaponSession;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

final class TaczWeaponAdapter implements WeaponAdapter {

    @Override
    public boolean ranged() {
        return true;
    }

    @Override
    public double range() {
        return TaczConfig.rangeCap();
    }

    @Override
    public boolean accepts(ItemStack stack) {
        return TaczGuns.supported(stack);
    }

    @Override
    public boolean usable(Worker worker, ItemStack stack) {
        return worker.body() instanceof ResidentEntity
            && accepts(stack)
            && (TaczGuns.loaded(stack) > 0 || hasAmmo(worker, stack));
    }


    static boolean hasAmmo(Worker worker, ItemStack gun) {
        return hasAmmo(worker.pack(), gun);
    }

    private static boolean hasAmmo(Container pack, ItemStack gun) {
        for (int slot = 0; slot < pack.getContainerSize(); slot++) {
            ItemStack ammo = pack.getItem(slot);
            if (TaczAmmoInventory.ordinary(ammo) && ((IAmmo) ammo.getItem()).isAmmoOfGun(gun, ammo)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double range(Worker worker, ItemStack weapon) {
        return TaczGuns.range(weapon);
    }

    @Override
    public Optional<WeaponSession> open(Worker worker) {
        return open(worker, ItemStack.EMPTY);
    }

    @Override
    public Optional<WeaponSession> open(Worker worker, ItemStack selected) {
        if (!(worker.body() instanceof ResidentEntity body)
            || body.hasWeaponSession()
            || worker.pack() != body.pack()
            || !body.isAlive()
            || body.isRemoved()
            || !(body.level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()) {
            return Optional.empty();
        }
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            if (!selected.isEmpty() && body.pack().getItem(slot) != selected) {
                continue;
            }
            if (!usable(worker, body.pack().getItem(slot))) {
                continue;
            }
            ItemStack actual = body.pack().removeItemNoUpdate(slot);
            TaczWeaponSession session = new TaczWeaponSession(worker, this);
            body.claimWeaponHand(session, actual);
            session.draw();
            return Optional.of(session);
        }
        return Optional.empty();
    }
}
