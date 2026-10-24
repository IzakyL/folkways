package io.github.izakyl.folkways.plugins.combat.weapon;

import net.minecraft.world.entity.LivingEntity;

/** One exclusive weapon loan, advanced only by the body's current work node on the server thread. */
public interface WeaponSession extends AutoCloseable {
    enum State { READY, FIRING, RELOADING, STOPPING, CLOSED }

    State state();

    /** Queue one attack. Each tick and the eventual discharge recheck hostility, sight and range. */
    boolean fire(LivingEntity target);

    /** Load a crossbow without firing it. False means unavailable ammunition or unsupported reload. */
    boolean reload();

    void tick();

    /** Reject further fire intents and cancel charging without releasing a bow or spawning a projectile. */
    void stop();

    /** Stop, then return the actual remaining item once. Safe to call repeatedly. */
    @Override
    void close();
}
