package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
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

    Ending DONE = new Done();
    Ending DROPPED = new Dropped();
    Ending REVOKED = new Revoked();
}
