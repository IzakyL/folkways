package io.github.izakyl.folkways.core.front.api;

import java.util.List;

public record Enrollment(List<Delegation> delegations, Schema settings) {

    public Enrollment(List<Delegation> delegations) {
        this(delegations, Schema.none());
    }

    public static Enrollment none() {
        return new Enrollment(List.of());
    }
}
