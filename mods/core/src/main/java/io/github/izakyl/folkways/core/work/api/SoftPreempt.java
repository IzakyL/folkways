package io.github.izakyl.folkways.core.work.api;

public record SoftPreempt(Disposition result, PreemptReason reason, long deadlineTick) {
}
