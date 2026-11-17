package io.github.izakyl.folkways.airline;

import java.util.Locale;

final class AirportNumbers {
    private AirportNumbers() {
    }

    static String number(double value) {
        return Double.isFinite(value) ? String.format(Locale.ROOT, Math.abs(value) >= 10 ? "%.1f" : "%.2f", value)
            : "—";
    }
}
