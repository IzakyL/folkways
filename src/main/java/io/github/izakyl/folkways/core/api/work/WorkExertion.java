package io.github.izakyl.folkways.core.api.work;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;

/** Execution totals, reported once when work or a walking segment is released.
 * Consumers decide whether and how their residents use them.
 * Work is measured in standard labor seconds, time in actual active ticks, distance in blocks.
 * Partial work is included regardless of whether execution completed, failed or was interrupted.
 */
public final class WorkExertion extends Event {
    private final LivingEntity body;
    private final double workDone;
    private final int activeTicks;
    private final double distance;

    private WorkExertion(LivingEntity body, double workDone, int activeTicks, double distance) {
        this.body = body;
        this.workDone = workDone;
        this.activeTicks = activeTicks;
        this.distance = distance;
    }

    public static void performed(LivingEntity body, double workDone, int activeTicks, double distance) {
        if (!Double.isFinite(workDone) || workDone < 0 || activeTicks < 0
                || !Double.isFinite(distance) || distance < 0) {
            throw new IllegalArgumentException("execution statistics must be finite and nonnegative");
        }
        if (!body.level().isClientSide && (workDone > 0 || activeTicks > 0 || distance > 0)) {
            NeoForge.EVENT_BUS.post(new WorkExertion(body, workDone, activeTicks, distance));
        }
    }

    public LivingEntity body() { return body; }
    public double workDone() { return workDone; }
    public int activeTicks() { return activeTicks; }
    public double distance() { return distance; }
}
