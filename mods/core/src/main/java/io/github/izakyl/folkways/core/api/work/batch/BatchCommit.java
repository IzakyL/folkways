package io.github.izakyl.folkways.core.api.work.batch;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.work.Outcome;
import java.util.Objects;
import java.util.Optional;

// Done commits supplied minus unused. Failed means no world change and restores all inputs.
// Once an appliance owns the inputs, report accepted even if the session must stop afterwards.
public record BatchCommit(Outcome outcome, Optional<RefusalKind> stop) {
    public BatchCommit {
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(stop);
        if (stop.isPresent() && !(outcome instanceof Outcome.Done)) {
            throw new IllegalArgumentException("only committed work can stop after accepting inputs");
        }
    }

    public static BatchCommit of(Outcome outcome) { return new BatchCommit(outcome, Optional.empty()); }

    public static BatchCommit accepted(Outcome.Done outcome, RefusalKind stop) {
        return new BatchCommit(outcome, Optional.of(stop));
    }
}
