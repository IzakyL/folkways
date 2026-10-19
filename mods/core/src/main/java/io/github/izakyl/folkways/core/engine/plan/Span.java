package io.github.izakyl.folkways.core.engine.plan;

public record Span(int from, int to) {

    public boolean overlaps(Span other) {
        return from < other.to && other.from < to;
    }
}
