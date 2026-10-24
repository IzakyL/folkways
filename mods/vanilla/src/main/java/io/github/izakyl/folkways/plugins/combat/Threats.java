package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Spider;

/** Vanilla's everyday hostility policy; neutral monsters must choose hostility themselves. */
public final class Threats {

    private Threats() {
    }

    public static boolean hostile(Mob mob, LivingEntity resident) {
        return mob != resident && (mob instanceof Enemy || mob.getType().is(CombatTags.PATROL_TARGETS))
            && !mob.getType().is(CombatTags.IGNORED) && mob.isAlive() && !mob.isRemoved()
            && Bodies.of(mob).flatMap(body -> body.colonyId()).isEmpty()
            && !mob.isAlliedTo(resident)
            && (!(mob instanceof Spider) || mob.getLightLevelDependentMagicValue() < .5F
                || mob.getTarget() != null && mob.getTarget().isAlive())
            && (!(mob instanceof NeutralMob neutral) || mob.getTarget() == resident || neutral.isAngryAt(resident));
    }
}
