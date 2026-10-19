package io.github.izakyl.folkways.front.api;

import io.github.izakyl.folkways.front.api.panel.PageKind;
import java.util.List;

public record Enrollment(List<Delegation> delegations, Schema settings, List<PageKind> pages) {

    public Enrollment(List<Delegation> delegations) {
        this(delegations, Schema.none(), List.of());
    }

    public Enrollment(List<Delegation> delegations, Schema settings) {
        this(delegations, settings, List.of());
    }

    public static Enrollment none() {
        return new Enrollment(List.of());
    }
}
