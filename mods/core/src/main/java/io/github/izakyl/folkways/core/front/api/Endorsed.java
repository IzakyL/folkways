package io.github.izakyl.folkways.core.front.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.front.api.notice.Notice;

public sealed interface Endorsed {

    record Joins(Body body) implements Endorsed {
    }

    record Leaves(Body body) implements Endorsed {
    }

    record Refused(Notice why) implements Endorsed {
    }

    record Pending() implements Endorsed {
    }
}
