package io.github.izakyl.folkways.core.engine.travel;

import java.util.Comparator;

public record Urgency(Band band, int rank) implements Comparable<Urgency> {

    public enum Band {
        WALKING,
        PLANNING,
        CHECKING,
        BACKGROUND
    }

    public static final Urgency WALKING = new Urgency(Band.WALKING, 0);
    public static final Urgency PLANNING = new Urgency(Band.PLANNING, 0);
    public static final Urgency CHECKING = new Urgency(Band.CHECKING, 0);
    public static final Urgency BACKGROUND = new Urgency(Band.BACKGROUND, 0);

    private static final Comparator<Urgency> ORDER =
        Comparator.comparing(Urgency::band).thenComparingInt(Urgency::rank);

    public static Urgency walking(int rank) {
        return new Urgency(Band.WALKING, rank);
    }

    public static Urgency planning(int rank) {
        return new Urgency(Band.PLANNING, rank);
    }

    @Override
    public int compareTo(Urgency other) {
        return ORDER.compare(this, other);
    }
}
