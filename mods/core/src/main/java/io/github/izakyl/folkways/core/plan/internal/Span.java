package io.github.izakyl.folkways.core.plan.internal;

record Span(int from, int to) {

    public boolean overlaps(Span other) {
        return from < other.to && other.from < to;
    }
}
