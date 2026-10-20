package io.github.izakyl.folkways.base.build;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The release's installed modules must describe the same source and dependency configuration. */
public final class ModuleBuilds {

    public static final String PROPERTY = "folkwaysBuildIdentity";
    public static final Set<String> MODULES = Set.of("folkways_core", "folkways_vanilla", "folkways_create",
        "folkways_modulargolems", "folkways_cookery");

    private ModuleBuilds() {
    }

    public record Mismatch(String module, String expected, String found) {
    }

    public static List<Mismatch> mismatches(Map<String, ?> installed) {
        String expected = identity(installed.get("folkways_core"));
        List<Mismatch> result = new ArrayList<>();
        if (expected == null) {
            result.add(new Mismatch("folkways_core", "a valid build identity", "missing or invalid"));
        }
        for (String module : MODULES.stream().sorted().toList()) {
            if (module.equals("folkways_core") || !installed.containsKey(module)) continue;
            String found = identity(installed.get(module));
            if (found == null || expected != null && !expected.equals(found)) {
                result.add(new Mismatch(module, expected == null ? "a valid build identity" : expected,
                    found == null ? "missing or invalid" : found));
            }
        }
        return List.copyOf(result);
    }

    private static String identity(Object value) {
        return value instanceof String text && text.matches("sha256:[0-9a-f]{64}") ? text : null;
    }
}
