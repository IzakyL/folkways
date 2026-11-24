package io.github.izakyl.folkways.core.shell.tacz;

import io.github.izakyl.folkways.core.combat.api.WeaponHands;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/** Binds native delayed burst tasks to the resident's current gun loan. */
public final class TaczBurstSession {

    private static final Map<WeaponHands, TaczWeaponAttack> ACTIVE = new IdentityHashMap<>();

    private TaczBurstSession() {
    }

    static void claim(WeaponHands body, TaczWeaponAttack attack) {
        ACTIVE.put(body, attack);
    }

    static void release(WeaponHands body, TaczWeaponAttack attack) {
        ACTIVE.remove(body, attack);
    }

    public static BooleanSupplier capture(LivingEntity shooter, ItemStack gun, BooleanSupplier task) {
        if (!(shooter instanceof WeaponHands body)) {
            return task;
        }
        TaczWeaponAttack loan = ACTIVE.get(body);
        return () -> loan != null && ACTIVE.get(body) == loan && loan.burstActive() && task.getAsBoolean();
    }
}
