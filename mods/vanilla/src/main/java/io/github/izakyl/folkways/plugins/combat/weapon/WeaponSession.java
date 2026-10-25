package io.github.izakyl.folkways.plugins.combat.weapon;

import net.minecraft.world.entity.LivingEntity;

/** One exclusive weapon loan, advanced only by the body's current work node on the server thread. */
public interface WeaponSession extends AutoCloseable {
    enum State { READY, FIRING, RELOADING, STOPPING, CLOSED }

    State state();

    /** Native animation collision may finish a target between the runner's ordinary ticks. */
    default boolean defeatedTarget() { return false; }

    /** Queue one attack. Ticks and discharge recheck hostility or the explicit lease, plus sight and range. */
    boolean fire(LivingEntity target);

    /** A single explicit target and live order lease. Adapters must opt in; ordinary fire stays hostile-only. */
    default boolean explicitTarget(LivingEntity target, java.util.function.BooleanSupplier authorized) {
        return false;
    }

    /** Load a crossbow without firing it. False means unavailable ammunition or unsupported reload. */
    boolean reload();

    void tick();

    /** Reject further fire intents and cancel charging without releasing a bow or spawning a projectile. */
    void stop();

    /** Stop, then return the actual remaining item once. Safe to call repeatedly. */
    @Override
    void close();
}
