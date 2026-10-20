package io.github.izakyl.folkways.base.task;

import org.slf4j.LoggerFactory;

// A failed cleanup must not prevent the remaining resources from being released.
public final class Cleanup implements AutoCloseable {

    private final String context;
    private RuntimeException failures;

    public Cleanup(String context) {
        this.context = context;
    }

    public void run(String phase, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error broken) {
            if (broken instanceof VirtualMachineError fatal) {
                throw fatal;
            }
            if (broken instanceof ThreadDeath fatal) {
                throw fatal;
            }
            if (failures == null) {
                failures = new IllegalStateException(context);
            }
            failures.addSuppressed(new IllegalStateException(phase, broken));
        }
    }

    @Override
    public void close() {
        RuntimeException report = failures;
        failures = null;
        if (report != null) {
            LoggerFactory.getLogger("folkways-lifecycle").error("{} failed during cleanup", context, report);
        }
    }
}
