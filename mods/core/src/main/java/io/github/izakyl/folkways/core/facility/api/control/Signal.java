package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * A control that is set to a value: writing the same value twice is the same as writing it once. A pulse is the one
 * exception, pressed rather than set.
 */
public non-sealed interface Signal extends Control {

    SignalType type();

    default Optional<String> check(Value value) {
        return type().check(value);
    }

    /** The fixed wind-up for one write, in ticks. */
    default int writeTicks() {
        return cost() instanceof Cost.Ticks(int ticks) ? ticks : 0;
    }

    /**
     * Sets the signal, on the server thread, with the writer at its stance and holding its lease. The value has passed
     * {@link #check}. A remote write may have a null writer. Anything but {@link Write#WRITTEN} leaves the signal
     * as it was.
     */
    Write write(ServerLevel level, Mob writer, Value value);

    /**
     * The reading that shows what the signal is set to, when the world shows it: a lever's power, a gearshift's
     * state. A routine waits on it to know a write took; without it, a write is done once committed.
     */
    default Optional<Reading> feedback() {
        return Optional.empty();
    }

    /** Feedback expressed in the units accepted by a write. */
    default Optional<Reading> commandFeedback() {
        return feedback();
    }

    /** The world position driven by this destination signal, when it supports automatic repositioning. */
    default Optional<Vec3> travelPosition(ServerLevel level) {
        return Optional.empty();
    }

    /** The native post a facility must staff to write this signal, when the signal has one. */
    default Optional<PostKey> requiredPost(ServerLevel level) {
        return Optional.empty();
    }

    /** Starts native preparation for a scheduled pulse without firing it. */
    default void preparePulse(ServerLevel level, Mob writer) {
    }

    /** Presses a pulse and reports its final result. A queued native pulse can settle later on the server thread. */
    default Write press(ServerLevel level, Mob writer, Consumer<Write> settled) {
        Write result = write(level, writer, Value.PULSE);
        if (result == Write.WRITTEN) {
            settled.accept(result);
        }
        return result;
    }

    enum Write {
        WRITTEN,
        // Not now: the control is in use or still moving, and may take the value later.
        BUSY,
        // Not this value, or not by a resident.
        REFUSED
    }
}
