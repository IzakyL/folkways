package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.front.api.notice.Notice;

public sealed interface Endorsed {

    record Joins(Body body) implements Endorsed {
    }

    record Leaves(Body body) implements Endorsed {
    }

    record Refused(Notice why) implements Endorsed {
    }
}
