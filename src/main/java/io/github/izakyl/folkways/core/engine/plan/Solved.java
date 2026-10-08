package io.github.izakyl.folkways.core.engine.plan;

public record Solved(Weave weave, Schedule schedule, Diagnosis diagnosis, Asks asks) {

    public static final Solved NOTHING =
        new Solved(Weave.EMPTY, Schedule.EMPTY, Diagnosis.NOTHING, Asks.NONE);
}
