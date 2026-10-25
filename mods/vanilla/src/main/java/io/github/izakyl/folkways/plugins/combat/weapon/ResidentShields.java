package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.common.ItemAbilities;

/** Defense uses the one real offhand stack and LivingEntity's normal directional damage path. */
public final class ResidentShields {
    private ResidentShields() { }

    /**
     * Request defense during an adapter's idle or early melee windup window. Bow/crossbow charging and
     * EPF FIRING must call lower first and retain exclusive use until their own native action finishes.
     * True means offhand use has started; vanilla still requires five ticks before isBlocking is true.
     */
    public static boolean guard(ResidentEntity resident, LivingEntity target) {
        if (!(resident.level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || !resident.isAlive() || resident.isRemoved() || target == null || !target.isAlive()
                || target.isRemoved() || target.level() != level || !resident.hasLineOfSight(target)
                || resident.shieldCooldownTicks() > 0
                || !resident.getOffhandItem().canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            lower(resident);
            return false;
        }
        // Never release or interrupt the main hand: it may contain an already drawn bow or loaded crossbow.
        if (resident.isUsingItem() && (resident.getUsedItemHand() != InteractionHand.OFF_HAND
                || resident.getUseItem() != resident.getOffhandItem())) return false;
        double x = target.getX() - resident.getX();
        double z = target.getZ() - resident.getZ();
        resident.setYHeadRot((float) (Mth.atan2(z, x) * Mth.RAD_TO_DEG) - 90);
        resident.getLookControl().setLookAt(target);
        resident.startUsingItem(InteractionHand.OFF_HAND);
        return resident.isUsingItem() && resident.getUsedItemHand() == InteractionHand.OFF_HAND
            && resident.getUseItem() == resident.getOffhandItem();
    }

    /** Cancel only offhand shield use, without calling releaseUsingItem (which could shoot a bow). */
    public static void lower(ResidentEntity resident) {
        if (resident.isUsingItem() && resident.getUsedItemHand() == InteractionHand.OFF_HAND
                && resident.getUseItem().canPerformAction(ItemAbilities.SHIELD_BLOCK)) {
            resident.stopUsingItem();
        }
    }
}
