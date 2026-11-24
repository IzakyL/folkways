package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.combat.api.Combatant;
import io.github.izakyl.folkways.core.combat.api.Vehicles;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.combat.api.WeaponRules;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfile;
import io.github.izakyl.folkways.vanilla.combat.weapon.LoadoutProfiles;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentWeapons;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import io.github.izakyl.folkways.vanilla.person.fear.FearContent;
import io.github.izakyl.folkways.vanilla.person.living.LivingContent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

/** Vanilla's weapons, seats, hostility and fear for every commanded resident, whatever its kind. */
public final class VanillaCombatant implements Combatant {

    @Override
    public boolean freeToCommand(Mob mob) {
        return !mob.isPassenger() || Seating.holds(mob) || ResidentMounts.riding(mob)
            || Bodies.of(mob)
                .filter(body -> Vehicles.of(mob.getVehicle())
                    .filter(provider -> provider.ownsSeat(body)).isPresent()).isPresent();
    }

    @Override
    public boolean engaged(Body body) {
        return body.mob() instanceof ResidentEntity person && person.weaponDrawn();
    }

    @Override
    public void disengage(Body body) {
        Lease.release(body.id());
        Seating.rise(body.mob());
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
        return ResidentWeapons.range(who);
    }

    @Override
    public double range(Worker who, LivingEntity target) {
        return ResidentWeapons.range(who, target);
    }

    @Override
    public boolean ranged(Worker who, LivingEntity target) {
        return ResidentWeapons.ranged(who, target);
    }

    @Override
    public WeaponAttack select(Worker who, LivingEntity target, WeaponAttack current) {
        return ResidentWeapons.select(who, target, current);
    }

    @Override
    public double range(Worker who, Entity target) {
        return ResidentWeapons.range(who, target);
    }

    @Override
    public boolean ranged(Worker who, Entity target) {
        return ResidentWeapons.ranged(who, target);
    }

    @Override
    public WeaponAttack select(Worker who, Entity target, WeaponAttack current) {
        return ResidentWeapons.select(who, target, current);
    }

    @Override
    public Map<String, Component> weapons(Body body) {
        var weapons = new LinkedHashMap<>(Combatant.super.weapons(body));
        if (body.mob().level() instanceof ServerLevel level) {
            body.colonyId().flatMap(id -> Colonies.of(level.getServer(), id))
                .flatMap(colony -> LoadoutProfiles.of(colony)
                    .assigned(body.id())).ifPresent(profile -> profile.entries().stream()
                        .filter(entry -> entry.kind()
                            == LoadoutProfile.Kind.WEAPON)
                        .flatMap(entry -> EquipmentProfile
                            .samples(entry.items()).stream())
                        .forEach(stack -> weapons.putIfAbsent(
                            WeaponRules.category(stack), stack.getHoverName())));
        }
        return Map.copyOf(weapons);
    }

    @Override
    public boolean hostile(Mob foe, LivingEntity resident) {
        return Threats.hostile(foe, resident);
    }

    @Override
    public boolean hostile(Mob foe) {
        return Threats.hostile(foe, foe.getTarget());
    }

    @Override
    public boolean colonyPet(Entity entity, UUID colony) {
        return LivingContent.colonyPet(entity, colony);
    }

    @Override
    public boolean fleeing(Body body) {
        return body.doing()
            .filter(doing -> doing.toward().orElse(doing.what()).equals(FearContent.FLEEING))
            .isPresent();
    }
}
