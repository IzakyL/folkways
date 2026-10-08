package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import java.util.Optional;

public sealed interface Attempt {

    record Went() implements Attempt {
    }

    record Refused(Notice why) implements Attempt {
    }

    static Attempt went() {
        return new Went();
    }

    static Attempt refused(NoticeKind kind, Notice.Arg... args) {
        return new Refused(Notice.of(kind, args));
    }

    static Attempt refused(RefusalKind why, Notice.Arg... args) {
        return new Refused(Notice.of(why, args));
    }

    default boolean refused() {
        return this instanceof Refused;
    }

    default Optional<Notice> refusal() {
        return this instanceof Refused refused ? Optional.of(refused.why()) : Optional.empty();
    }
}
