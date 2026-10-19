package io.github.izakyl.folkways.core.engine.plan;

import java.util.List;

public record Diagnosis(List<Shortfall> shortfalls, List<Unassigned> unassigned,
                        List<Refused> refused) {

    public Diagnosis {
        shortfalls = List.copyOf(shortfalls);
        unassigned = List.copyOf(unassigned);
        refused = List.copyOf(refused);
    }

    public static final Diagnosis NOTHING = new Diagnosis(List.of(), List.of(), List.of());
}
