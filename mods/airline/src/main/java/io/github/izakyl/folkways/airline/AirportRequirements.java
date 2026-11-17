package io.github.izakyl.folkways.airline;

/** Minimum facility dimensions, in blocks. */
public record AirportRequirements(int helipadSize, int standLength, int standWidth, boolean verticalTakeoff) {
    public AirportRequirements {
        if (helipadSize < 0 || standLength < 0 || standWidth < 0) {
            throw new IllegalArgumentException("Facility dimensions cannot be negative");
        }
    }
}
