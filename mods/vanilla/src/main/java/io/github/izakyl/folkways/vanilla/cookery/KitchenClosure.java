package io.github.izakyl.folkways.vanilla.cookery;

import io.github.izakyl.folkways.core.work.api.Ending;
import java.util.function.Consumer;
import org.slf4j.LoggerFactory;

// Node resources outlive a suspended body and close only when the node ends.
public final class KitchenClosure {
    private final Consumer<Ending> release;
    private final Runnable changed;
    private final String refreshFailure;
    private Runnable unlisten = () -> { };
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

    public void onWithdrawal(KitchenBookings bookings, io.github.izakyl.folkways.core.world.api.WorldPos at,
            Runnable withdrawn) {
        unlisten.run();
        if (!closed) {
            unlisten = bookings.whenWithdrawn(at, withdrawn);
        }
    }

    public void close(Ending how) {
        if (closed) {
            return;
        }
        closed = true;
        unlisten.run();
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
