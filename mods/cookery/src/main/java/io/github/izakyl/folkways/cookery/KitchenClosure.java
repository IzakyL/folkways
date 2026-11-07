package io.github.izakyl.folkways.cookery;

import io.github.izakyl.folkways.core.work.api.Ending;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

// Node resources outlive a suspended body and close only when the node ends.
public final class KitchenClosure {
    private final Consumer<Ending> release;
    private final Runnable changed;
    private final String refreshFailure;
    private boolean closed;

    public KitchenClosure(Consumer<Ending> release, Runnable changed) {
        this(release, changed, null);
    }

    public KitchenClosure(Consumer<Ending> release, Runnable changed, String refreshFailure) {
        this.release = release;
        this.changed = changed;
        this.refreshFailure = refreshFailure;
    }

    public boolean closed() {
        return closed;
    }

    public void close(Ending how) {
        if (closed) {
            return;
        }
        closed = true;
        release.accept(how);
        refresh();
    }

    public void refresh() {
        if (refreshFailure == null) {
            changed.run();
            return;
        }
        try {
            changed.run();
        } catch (RuntimeException | LinkageError broken) {
            LoggerFactory.getLogger("folkways-cookery").warn(refreshFailure, broken);
        }
    }
}
