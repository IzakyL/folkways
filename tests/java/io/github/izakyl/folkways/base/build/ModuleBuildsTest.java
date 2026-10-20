package io.github.izakyl.folkways.base.build;

import java.util.HashMap;
import java.util.Map;

/** Isolated Java tests, including legacy jars and legal optional-module combinations. */
public final class ModuleBuildsTest {
    private static final String CURRENT = "sha256:" + "a".repeat(64);
    private static final String OTHER = "sha256:" + "b".repeat(64);

    public static void main(String[] args) {
        var installed = new HashMap<String, Object>();
        installed.put("folkways_core", CURRENT);
        check(ModuleBuilds.mismatches(installed).isEmpty(), "Core alone is supported");
        var optional = ModuleBuilds.MODULES.stream().filter(id -> !id.equals("folkways_core")).sorted().toList();
        for (int mask = 0; mask < 1 << optional.size(); mask++) {
            var combination = new HashMap<>(installed);
            for (int i = 0; i < optional.size(); i++) {
                if ((mask & 1 << i) != 0) combination.put(optional.get(i), CURRENT);
            }
            check(ModuleBuilds.mismatches(combination).isEmpty(), "same-source optional subset " + mask);
        }
        installed.put("folkways_vanilla", OTHER);
        check(ModuleBuilds.mismatches(installed).size() == 1, "different build must fail");
        check(ModuleBuilds.mismatches(installed).getFirst().module().equals("folkways_vanilla"), "name faulty module");
        installed.put("folkways_vanilla", null);
        check(ModuleBuilds.mismatches(installed).size() == 1, "legacy module without metadata must fail");
        installed.put("folkways_vanilla", "unknown");
        check(ModuleBuilds.mismatches(installed).size() == 1, "unknown identity must not count as matching");
        installed.put("folkways_vanilla", 12);
        check(ModuleBuilds.mismatches(installed).size() == 1, "malformed property must fail cleanly");
        check(!ModuleBuilds.mismatches(Map.of("folkways_core", "unknown", "folkways_vanilla", "unknown")).isEmpty(),
            "two unknown identities do not match");
        installed.put("folkways_vanilla", CURRENT);
        installed.put("another_mod", OTHER);
        check(ModuleBuilds.mismatches(installed).isEmpty(), "unrelated mods are not constrained");
        System.out.println("ModuleBuildsTest: optional subsets, mixed builds and invalid identities passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
