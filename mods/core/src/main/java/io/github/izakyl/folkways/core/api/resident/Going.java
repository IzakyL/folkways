package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public sealed interface Going {

    record Arrived() implements Going {
    }

    record Underway() implements Going {
    }

    record Failed(RefusalKind why) implements Going {
    }

    Going ARRIVED = new Arrived();
    Going UNDERWAY = new Underway();
}
