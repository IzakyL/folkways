package io.github.izakyl.folkways.plugins.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;

/** Vanilla's everyday hostility policy; neutral monsters must choose hostility themselves. */
public final class Threats {

    private Threats() {
    }

    public static boolean hostile(Mob mob, LivingEntity resident) {
        return mob != resident && mob instanceof Enemy && mob.isAlive() && !mob.isRemoved()
            && !mob.isAlliedTo(resident)
            && (!(mob instanceof NeutralMob neutral) || mob.getTarget() == resident || neutral.isAngryAt(resident));
    }
}
