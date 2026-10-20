package io.github.izakyl.folkways.core.api.work.batch;

import java.util.Objects;
import java.util.UUID;

// The root identifies related stays; the enclosing node, not the root, owns the input budget.
// maxWaitTicks bounds one polling interval, not the lifetime of an appliance's real processing.
public record BatchSpec(UUID root, int maxContinuousSteps, int maxWaitTicks) {
    public BatchSpec {
        Objects.requireNonNull(root);
        if (maxContinuousSteps < 1 || maxWaitTicks < 1) {
            throw new IllegalArgumentException("a batch needs positive step and wait limits");
        }
    }

    public static BatchSpec of(UUID root) {
        return new BatchSpec(root, 32, 200);
    }
}
