package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.fear.FearContent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Vanilla's weapons, seats, hostility and fear for every commanded resident, whatever its kind. */
public final class VanillaCombatant implements Combatant {

    @Override
    public boolean freeToCommand(Mob mob) {
        return !mob.isPassenger() || WeaponStations.ownsSeat(mob) || ResidentMounts.riding(mob);
    }

    @Override
    public boolean engaged(Body body) {
        return body.mob() instanceof ResidentEntity person && person.weaponDrawn();
    }

    @Override
    public void disengage(Body body) {
        WeaponStations.release(body.id());
        stand(body);
    }

    @Override
    public void stand(Body body) {
        if (body.mob() instanceof ResidentEntity person) {
            person.stopCombat();
        }
    }

    @Override
    public double range(Worker who) {
        return Weapons.range(who);
    }

    @Override
    public double range(Worker who, LivingEntity target) {
        return Weapons.range(who, target);
    }

    @Override
    public boolean ranged(Worker who, LivingEntity target) {
        return Weapons.ranged(who, target);
    }

    @Override
    public WeaponAttack select(Worker who, LivingEntity target, WeaponAttack current) {
        return Weapons.select(who, target, current);
    }

    @Override
    public boolean hostile(Mob foe, LivingEntity resident) {
        return Threats.hostile(foe, resident);
    }

    @Override
    public boolean fleeing(Body body) {
        return body.doing()
            .filter(doing -> doing.toward().orElse(doing.what()).equals(FearContent.FLEEING))
            .isPresent();
    }
}
