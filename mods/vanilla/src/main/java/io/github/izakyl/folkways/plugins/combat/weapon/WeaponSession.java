package io.github.izakyl.folkways.plugins.combat.weapon;

import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** One exclusive weapon loan, advanced only by the body's current work node on the server thread. */
public interface WeaponSession extends AutoCloseable {
    enum State {
        READY,
        FIRING,
        RELOADING,
        STOPPING,
        CLOSED
    }

    State state();

    /** Keep the same hand loan between attacks while this node still owns the engagement. */
    default boolean continuousEngagement() {
        return false;
    }

    /** Queue one attack. Ticks and discharge recheck hostility or the explicit lease, plus sight and range. */
    boolean fire(LivingEntity target);

    /** Opt in to firing at an immutable observation; recheck permission immediately before every discharge. */
    default boolean spottedTarget(LivingEntity target, Vec3 observed, BooleanSupplier authorized) {
        return false;
    }

    /** A single explicit target and live order lease. Adapters must opt in; ordinary fire stays hostile-only. */
    default boolean explicitTarget(LivingEntity target, BooleanSupplier authorized) {
        return false;
    }

    /**
     * The owning node's live engagement lease: false once the node stops advancing (suspended, ended or paused)
     * or its own authorization lapses. Sessions that discharge only inside {@link #tick()} may ignore it; a session
     * that can discharge outside the node's tick (for example from the entity tick) must recheck it every time.
     */
    default void leasedBy(BooleanSupplier lease) {
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
