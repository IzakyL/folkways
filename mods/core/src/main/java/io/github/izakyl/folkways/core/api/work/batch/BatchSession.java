package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import net.minecraft.server.level.ServerLevel;

// Declare aggregate needs on the enclosing NodeSpec. All callbacks run on the server thread.
// Persisted recovery must inspect the real world; this protocol is not a cross-save transaction.
public interface BatchSession extends Workload.Within {
    BatchSpec batch();

    StepResult advance(ServerLevel level, Worker who);

    default void settled(ServerLevel level, StepReceipt receipt) { }

    // A decorator may need ordinary return actions after its production step failed.
    default boolean drainsAfterFailure() { return false; }

    // Completion checks only: products, input consumption, effort and XP belong to the steps.
    default Outcome finish(ServerLevel level, Worker who) { return Outcome.done(); }

    @Override
    default Turn next(ServerLevel level, Worker who) {
        throw new IllegalStateException("batch sessions must run through the core batch adapter");
    }
}
