package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.RefusalKind;



public sealed interface HoldingStep {
    record Running() implements HoldingStep {
    }

    record Done() implements HoldingStep {
    }

    record Failed(RefusalKind why) implements HoldingStep {
    }

    record Yielded() implements HoldingStep {
    }
}
