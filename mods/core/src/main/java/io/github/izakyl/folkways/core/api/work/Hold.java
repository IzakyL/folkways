package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface Hold {

    Optional<RefusalKind> take();

    void release(Ending how);

    // Once taken: work already on the plan that what is held stays spoken for by until it is done. The work the
    // hold was taken for starts after it - a thing taken in turn, not at once.
    default Set<UUID> after() {
        return Set.of();
    }
}
