package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
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

    /** Explicit orders may target neutral mobs, while all resident bodies and allies stay protected. */
    public static boolean explicitlyAttackable(Mob mob, LivingEntity resident, LivingEntity commander) {
        return mob != resident && mob != commander && mob.level() == resident.level()
            && mob.level() == commander.level() && mob.isAlive() && !mob.isRemoved()
            && Bodies.of(mob).isEmpty() && !mob.isAlliedTo(resident) && !resident.isAlliedTo(mob)
            && !mob.isAlliedTo(commander) && !commander.isAlliedTo(mob)
            && (!(mob instanceof TamableAnimal tame) || !tame.isOwnedBy(commander));
    }
}
