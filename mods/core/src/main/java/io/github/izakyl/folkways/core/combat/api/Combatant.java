package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * How any resident fights under direct orders: weapons, hostility and seats. Core knows no weapons; the module
 * that does installs one combatant for every resident kind, and answers per body where kinds differ.
 */
public interface Combatant {
    /** Bare-handed and unseated: what core falls back to when no module supplies weapons. */
    Combatant UNARMED = new Combatant() {
    };

    /** Install the one combatant; later modules replace earlier ones. */
    static void install(Combatant combatant) {
        Holder.INSTALLED.set(combatant);
    }

    static Combatant get() {
        return Holder.INSTALLED.get();
    }

    /** A passenger answers orders only from its own weapon seat or mount. */
    default boolean freeToCommand(Mob mob) {
        return !mob.isPassenger();
    }

    /** Whether the body still holds a drawn weapon of its own, outside any order's attack node. */
    default boolean engaged(Body body) {
        return false;
    }

    /** A new order takes the body: leave weapon seats and end its own drawn weapon. */
    default void disengage(Body body) {
    }

    /** An order ends: stop any attack it started, keeping seats. */
    default void stand(Body body) {
    }

    default double range(Worker who) {
        return 2.5;
    }

    default double range(Worker who, LivingEntity target) {
        return range(who);
    }

    default boolean ranged(Worker who, LivingEntity target) {
        return false;
    }

    /** The weapon to use against this target; reuse {@code current} while it still fits. Null means none. */
    default WeaponAttack select(Worker who, LivingEntity target, WeaponAttack current) {
        return current;
    }

    default double range(Worker who, Entity target) {
        return target instanceof LivingEntity living ? range(who, living) : range(who);
    }

    default boolean ranged(Worker who, Entity target) {
        return target instanceof LivingEntity living && ranged(who, living);
    }

    default WeaponAttack select(Worker who, Entity target, WeaponAttack current) {
        return target instanceof LivingEntity living ? select(who, living, current) : null;
    }

    /** Rule categories and localized weapon names offered by this body's current loadout. */
    default Map<String, Component> weapons(Body body) {
        var weapons = new LinkedHashMap<String, Component>();
        for (int slot = 0; slot < body.pack().getContainerSize(); slot++) {
            var stack = body.pack().getItem(slot);
            if (Loadouts.using(stack).isPresent()) {
                weapons.putIfAbsent(WeaponRules.category(stack), stack.getHoverName());
            }
        }
        return Map.copyOf(weapons);
    }

    /** Hostility that lets a holding squad defend itself without an explicit attack order. */
    default boolean hostile(Mob foe, LivingEntity resident) {
        return foe instanceof Enemy && foe.isAlive() && !foe.isAlliedTo(resident);
    }

    /** Fear outranks orders: a fleeing body yields its guard post. */
    default boolean fleeing(Body body) {
        return false;
    }

    final class Holder {
        private static final AtomicReference<Combatant> INSTALLED = new AtomicReference<>(UNARMED);

        private Holder() {
        }
    }
}
