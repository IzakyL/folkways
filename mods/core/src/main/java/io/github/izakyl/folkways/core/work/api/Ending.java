package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.UUID;

public sealed interface Ending {

    record Done() implements Ending {
    }

    record Dropped() implements Ending {
    }

    record Failed(UUID node, RefusalKind why) implements Ending {
    }

    record Revoked() implements Ending {
    }

    // Only sent to released(): the worker loses its placement while the node stays live.
    record Replaced() implements Ending {
    }

    Ending REPLACED = new Replaced();
    Ending DONE = new Done();
    Ending DROPPED = new Dropped();
    Ending REVOKED = new Revoked();
}
