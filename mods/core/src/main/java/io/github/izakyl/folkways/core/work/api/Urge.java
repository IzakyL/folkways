package io.github.izakyl.folkways.core.work.api;

import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public record Urge(
    ResourceLocation id,
    double weight,
    boolean preempts,
    Supplier<Node> take,
    Priority priority,
    boolean reservation,
    boolean urgent,
    Disposition disposition) {

    /** Deliberate control is a separate tier, independent of need-specific numeric weights. */
    public enum Priority {
        /** Personal activity that yields to ready colony work. */
        IDLE,
        ORDINARY,
        CONTROL
    }

    public Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take) {
        this(id, weight, preempts, take, Priority.ORDINARY, false, false);
    }

    public Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take, Priority priority) {
        this(id, weight, preempts, take, priority, false, false);
    }

    public Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take, Priority priority,
            boolean reservation, boolean urgent) {
        this(id, weight, preempts, take, priority, reservation, urgent, Disposition.SUSPEND);
    }

    public Urge withPreemption(Disposition disposition) {
        return new Urge(id, weight, preempts, take, priority, reservation, urgent, disposition);
    }

    // Urgency controls how the current holding is released, independently of its ranking weight.
    public Urge asUrgent() {
        return new Urge(id, weight, preempts, take, priority, reservation, true, disposition);
    }

    /** A passive controller reservation: excludes planned labour, while personal urges still use the runner. */
    public static Urge reserve(ResourceLocation id) {
        return new Urge(
            id,
            IDLE,
            false,
            () -> {
                throw new IllegalStateException("a passive reservation is not executable work");
            },
            Priority.ORDINARY,
            true,
            false);
    }

    public Urge {
        Objects.requireNonNull(priority);
        Objects.requireNonNull(disposition);
    }

    public boolean outranks(Urge other) {
        return priority != other.priority ? priority.compareTo(other.priority) > 0 : weight > other.weight;
    }

    public static final double IDLE = 0.05;

    public static final double SPARE = 0.5;

    public static final double NOW = 1.0;

    public static final double DIRE = 2.0;
}
