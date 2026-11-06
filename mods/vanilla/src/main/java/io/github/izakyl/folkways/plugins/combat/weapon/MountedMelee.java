package io.github.izakyl.folkways.plugins.combat.weapon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/** Bonuses are sampled at impact, after the weapon's wind-up. Speeds are blocks per tick. */
public final class MountedMelee {
    public static final double EXTRA_REACH = 1.5;
    public static final double FULL_CHARGE_SPEED = .16;
    public static final double MAX_DAMAGE_BONUS = .5;
    public static final double MAX_KNOCKBACK_BONUS = 1;
    private static final ResourceLocation CHARGE = ResourceLocation.fromNamespaceAndPath("folkways", "mounted_charge");

    private MountedMelee() {
    }

    public static double reach(Mob body) {
        return ResidentMounts.riding(body) ? EXTRA_REACH : 0;
    }

    static boolean hit(Mob body, LivingEntity target) {
        // Measure travel this tick: native delta movement has already been reduced by ground drag.
        double charge = ResidentMounts.horse(body).filter(horse -> body.getVehicle() == horse)
            .map(horse -> Math.min(1, Math.hypot(horse.getX() - horse.xo, horse.getZ() - horse.zo)
                / FULL_CHARGE_SPEED)).orElse(0.0);
        if (charge == 0) {
            return body.doHurtTarget(target);
        }
        var damage = body.getAttribute(Attributes.ATTACK_DAMAGE);
        Vec3 before = target.getDeltaMovement();
        damage.addTransientModifier(new AttributeModifier(CHARGE, charge * MAX_DAMAGE_BONUS,
            AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        boolean hit;
        try {
            hit = body.doHurtTarget(target);
        } finally {
            damage.removeModifier(CHARGE);
        }
        if (hit) {
            // Scale the native horizontal impulse, including weapon enchantments and knockback resistance.
            Vec3 after = target.getDeltaMovement();
            target.setDeltaMovement(after.add((after.x - before.x) * charge * MAX_KNOCKBACK_BONUS, 0,
                (after.z - before.z) * charge * MAX_KNOCKBACK_BONUS));
        }
        return hit;
    }
}
