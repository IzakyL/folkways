package io.github.izakyl.folkways.core.engine.plan;

// One round of planning as a test reads it: what the weave made of it, how the schedule laid it out, and the whole
// plan after it.
public record Planned(Planner.Woven woven, Scheduler.Scheduled laid, Shown weave) {

    public Schedule schedule() {
        return new Schedule(laid.all(), laid.unassigned());
    }

    public Diagnosis diagnosis() {
        return new Diagnosis(woven.shortfalls(), laid.unassigned(), woven.refused());
    }

    // What the colony is asked, the weave's asks and the schedule's together, as the colony puts them together.
    public Asks asks() {
        Asks woven = this.woven.asks();
        return new Asks(woven.survey(), woven.looks(), woven.hands() || laid.hands(), woven.handsOf(),
            Math.min(woven.wakeAt(), laid.wakeAt()));
    }
}
