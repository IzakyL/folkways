package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.concurrent.atomic.AtomicReference;
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
