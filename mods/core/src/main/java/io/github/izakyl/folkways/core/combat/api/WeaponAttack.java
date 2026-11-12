package io.github.izakyl.folkways.core.combat.api;

import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Transient attack behavior, advanced by the current work node; never moves physical items. */
public interface WeaponAttack {
    enum State {
        READY,
        FIRING,
        RELOADING,
        STOPPED
    }

    State state();

    /** Keep the weapon drawn between attacks while this node still owns the engagement. */
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
     * or its own authorization lapses. Attacks that discharge only inside {@link #tick()} may ignore it; an attack
     * that can discharge outside the node's tick (for example from the entity tick) must recheck it every time.
     */
    default void leasedBy(BooleanSupplier lease) {
    }

    /** Load a crossbow without firing it. False means unavailable ammunition or unsupported reload. */
    boolean reload();

    void tick();

    /** Reject further fire intents and cancel charging without releasing a bow or spawning a projectile. */
    void stop();

}
