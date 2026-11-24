package io.github.izakyl.folkways.solve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Allocates one prospective shot per unit. The caller supplies only feasible candidates. */
public final class Engagements {

    public record Target<T>(T id, double health, double threat, int priority, boolean marked) {
    }

    public record Candidate<T>(Target<T> target, double distanceSquared, double alignment, int neighbors,
                               boolean indirect) {
    }

    public record Unit<U, T>(U id, boolean loaded, int cycleTicks, double damage, List<Candidate<T>> candidates) {
    }

    private Engagements() {
    }

    public static <U, T> Map<U, T> allocate(List<Unit<U, T>> units) {
        List<Unit<U, T>> ordered = new ArrayList<>(units);
        ordered.sort(Comparator.<Unit<U, T>, Boolean>comparing(Unit::loaded).reversed()
            .thenComparingInt(unit -> unit.candidates().size()).thenComparingInt(Unit::cycleTicks));
        Map<T, Double> committed = new HashMap<>();
        Map<U, T> assigned = new LinkedHashMap<>();
        Comparator<Candidate<T>> preference = Comparator
            .<Candidate<T>, Boolean>comparing(candidate -> candidate.target().marked()).reversed()
            .thenComparing(Comparator.comparingInt((Candidate<T> candidate) -> candidate.target().priority()).reversed())
            .thenComparing(Comparator.comparingDouble((Candidate<T> candidate) -> candidate.target().threat()).reversed())
            .thenComparing(Comparator.comparingInt(
                (Candidate<T> candidate) -> candidate.indirect() ? candidate.neighbors() : 0).reversed())
            .thenComparingDouble(Candidate::distanceSquared)
            .thenComparing(Comparator.comparingDouble(Candidate<T>::alignment).reversed());
        for (Unit<U, T> unit : ordered) {
            unit.candidates().stream().filter(candidate -> {
                Target<T> target = candidate.target();
                return target.marked() || committed.getOrDefault(target.id(), 0.0) < target.health();
            }).min(preference).ifPresent(candidate -> {
                T target = candidate.target().id();
                assigned.put(unit.id(), target);
                committed.merge(target, unit.damage(), Double::sum);
            });
        }
        return Map.copyOf(assigned);
    }
}
