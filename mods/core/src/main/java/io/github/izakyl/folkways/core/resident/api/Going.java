package io.github.izakyl.folkways.core.resident.api;



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
