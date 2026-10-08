package io.github.izakyl.folkways.fuzz;

import java.util.Arrays;
import java.util.stream.Collectors;

/** What a case broke. {@code kind} names the property and keys shrinking and deduplication; {@code detail} says how. */
public record Violation(String kind, String detail) {

    public static Violation threw(Throwable error) {
        StackTraceElement ours = Arrays.stream(error.getStackTrace())
            .filter(frame -> frame.getClassName().startsWith("io.github.izakyl.folkways")
                && !frame.getClassName().endsWith("Fuzz"))
            .findFirst().orElse(error.getStackTrace().length > 0 ? error.getStackTrace()[0] : null);
        String where = ours == null ? "?" : ours.getClassName().substring(ours.getClassName().lastIndexOf('.') + 1)
            + "." + ours.getMethodName();
        String trace = Arrays.stream(error.getStackTrace()).limit(14).map(String::valueOf)
            .collect(Collectors.joining("\n  at "));
        return new Violation("threw:" + error.getClass().getSimpleName() + "@" + where, error + "\n  at " + trace);
    }
}
