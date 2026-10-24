package io.github.izakyl.folkways.core.api.work;

import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public record Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take, Priority priority) {

    /** Deliberate control is a separate tier, independent of need-specific numeric weights. */
    public enum Priority { ORDINARY, CONTROL }

    public Urge(ResourceLocation id, double weight, boolean preempts, Supplier<Node> take) {
        this(id, weight, preempts, take, Priority.ORDINARY);
    }

    public Urge {
        java.util.Objects.requireNonNull(priority);
    }

    public boolean outranks(Urge other) {
        return priority != other.priority ? priority.compareTo(other.priority) > 0 : weight > other.weight;
    }

    public static final double IDLE = 0.05;

    public static final double SPARE = 0.5;

    public static final double NOW = 1.0;

    public static final double DIRE = 2.0;
}
