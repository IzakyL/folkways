package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;

public sealed interface Why {

    record NoSource() implements Why {
    }

    record NoRoom() implements Why {
    }

    record NoWay() implements Why {
    }

    // No resident is fit to do the work the goods would need, together with what it must be done with.
    record NoHand() implements Why {
    }

    record Turned(RefusalKind why) implements Why {
    }

    record Cooling(Choice choice) implements Why {
    }

    Why NO_SOURCE = new NoSource();
    Why NO_ROOM = new NoRoom();
    Why NO_WAY = new NoWay();
    Why NO_HAND = new NoHand();
}
