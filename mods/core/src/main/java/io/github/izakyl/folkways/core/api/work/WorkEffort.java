package io.github.izakyl.folkways.core.api.work;

/** Abstract labor, independent of animation, world actions and the worker's physiology.
 * One unit is one standard labor second. Finite work declares a total; continuous work a rate.
 */
public sealed interface WorkEffort {
    WorkEffort NONE = new None();

    record None() implements WorkEffort { }

    record Total(double amount) implements WorkEffort {
        public Total { requireAmount(amount); }
    }

    record PerTick(double amount) implements WorkEffort {
        public PerTick { requireAmount(amount); }
    }

    static WorkEffort standard(Workload workload) {
        return workload instanceof Workload.Once once
            ? new Total(once.baseTicks() / 20.0D) : new PerTick(1.0D / 20.0D);
    }

    private static void requireAmount(double amount) {
        if (!Double.isFinite(amount) || amount < 0) {
            throw new IllegalArgumentException("work effort must be finite and nonnegative");
        }
    }
}
