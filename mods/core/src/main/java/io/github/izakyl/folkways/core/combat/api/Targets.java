package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;

/** Which mobs a player may explicitly order residents to attack, whatever their kind. */
public final class Targets {

    private Targets() {
    }

    public static boolean explicitlyAttackable(Entity target,
                                               LivingEntity resident, LivingEntity commander) {
        if (target instanceof Mob mob) {
            return explicitlyAttackable(mob, resident, commander);
        }
        return Vehicles.of(target).filter(provider -> provider.owner(target)
            .filter(owner -> Bodies.of(resident).flatMap(body -> body.colonyId()).filter(owner::equals).isPresent())
            .isEmpty()).isPresent() && target.isAlive() && !target.isRemoved()
            && target.level() == resident.level() && target.level() == commander.level()
            && !target.isAlliedTo(resident) && !target.isAlliedTo(commander)
            && target.getPassengers().stream().allMatch(passenger -> passenger instanceof Mob mob
                && explicitlyAttackable(mob, resident, commander));
    }

    /** Explicit orders may target neutral mobs, while all resident bodies and allies stay protected. */
    public static boolean explicitlyAttackable(Mob mob, LivingEntity resident, LivingEntity commander) {
        return mob != resident
            && mob != commander
            && mob.level() == resident.level()
            && mob.level() == commander.level()
            && mob.isAlive()
            && !mob.isRemoved()
            && Bodies.of(mob).isEmpty()
            && !mob.isAlliedTo(resident)
            && !resident.isAlliedTo(mob)
            && !mob.isAlliedTo(commander)
            && !commander.isAlliedTo(mob)
            && (!(mob instanceof TamableAnimal tame) || !tame.isOwnedBy(commander));
    }
}
