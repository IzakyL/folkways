package io.github.izakyl.folkways.core.testing;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.minecraft.gametest.framework.GameTestHelper;
import org.slf4j.Logger;

public final class PerfBudget {

    private static final Logger LOGGER = LogUtils.getLogger();

    private PerfBudget() {
    }

    public static void check(GameTestHelper helper, String test, double mean, long p95, long max,
                             long meanBudget, long p95Budget) {
        boolean warn = switch (System.getProperty("folkways.perfBudget", "strict")) {
            case "strict" -> false;
            case "warn" -> true;
            default -> throw new IllegalArgumentException("perfBudget must be strict or warn");
        };
        String message = test + ": " + timing(mean, p95, max)
            + "; budget mean/p95 " + meanBudget / 1_000 + " / " + p95Budget / 1_000 + " µs";
        if (warn) {
            helper.assertTrue(mean <= 4 * meanBudget && p95 <= 4 * p95Budget, message + " (exceeds 4x budget)");
            if (mean >= meanBudget || p95 >= p95Budget) {
                writeWarning(message);
            }
        } else {
            helper.assertTrue(mean < meanBudget && p95 < p95Budget, message);
        }
    }

    public static String timing(double mean, long p95, long max) {
        return "main mean/p95/max " + Math.round(mean / 1_000) + " / " + p95 / 1_000
            + " / " + max / 1_000 + " µs";
    }

    private static void writeWarning(String message) {
        LOGGER.warn("Performance budget: {}", message);
        try {
            // The GameTest server runs with gameTestDirectory as its working directory.
            Files.writeString(Path.of("perf-warnings.txt"), message + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | SecurityException exception) {
            LOGGER.warn("Could not write perf-warnings.txt", exception);
        }
    }
}
