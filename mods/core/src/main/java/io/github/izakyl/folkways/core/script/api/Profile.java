package io.github.izakyl.folkways.core.script.api;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import java.time.Duration;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import net.starlark.java.eval.StarlarkSemantics;

public record Profile(Lane lane, long steps, Duration time, int calls, int maxSource,
        StarlarkSemantics semantics, Map<String, Object> predeclared, boolean autoIndent,
        Function<String, ResourceLocation> libraryResolver, BiConsumer<ResourceLocation, String> print,
        long allocationBytes, long cumulativeAllocationBytes) {

    public Profile(Lane lane, long steps, Duration time, int calls, int maxSource,
            StarlarkSemantics semantics, Map<String, Object> predeclared, boolean autoIndent,
            Function<String, ResourceLocation> libraryResolver, BiConsumer<ResourceLocation, String> print) {
        this(lane, steps, time, calls, maxSource, semantics, predeclared, autoIndent, libraryResolver, print, 0, 0);
    }

    @Override
    public long allocationBytes() {
        return allocationBytes == 0 ? FolkwaysConfig.scriptAllocationBytes() : allocationBytes;
    }

    @Override
    public long cumulativeAllocationBytes() {
        return cumulativeAllocationBytes == 0 ? FolkwaysConfig.scriptCumulativeAllocationBytes()
            : cumulativeAllocationBytes;
    }

    public Profile withMemoryLimits(long allocationBytes, long cumulativeAllocationBytes) {
        if (allocationBytes <= 0 || cumulativeAllocationBytes <= 0) {
            throw new IllegalArgumentException("Script memory limits must be positive");
        }
        return new Profile(lane, steps, time, calls, maxSource, semantics, predeclared, autoIndent,
            libraryResolver, print, allocationBytes, cumulativeAllocationBytes);
    }

    private static final BiConsumer<ResourceLocation, String> SILENT = (id, message) -> { };

    public enum Lane {
        AGENT("agent", 8, 8),
        FACILITY_CONDITION("facility-condition", 2, 64),
        FACILITY_STEP("facility-step", 2, 32),
        DRAFT_PATTERN("draft", Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2)), 64);

        final Workers workers;
        private final int maxInFlight;

        Lane(String name, int threads, int maxInFlight) {
            this.workers = new Workers("folkways-" + name, threads, maxInFlight);
            this.maxInFlight = maxInFlight;
        }

        public int maxInFlight() {
            return maxInFlight;
        }
    }

    public Profile {
        predeclared = Map.copyOf(predeclared);
    }

    public static Profile agent() {
        return new Profile(Lane.AGENT, 1_000_000, Duration.ofSeconds(10), 2000, 8192,
            StarlarkSemantics.DEFAULT, Map.of(), true, label -> standardOr(label, Profile::colonyLibrary), SILENT);
    }

    public static Profile facilityCondition() {
        return facility(Lane.FACILITY_CONDITION, 1000, Map.of());
    }

    public static Profile facilityStep(Map<String, Object> actions) {
        // Room for a step that plans a whole job at once, such as a tool's stops through a picked box.
        return facility(Lane.FACILITY_STEP, 100_000, actions);
    }

    private static Profile facility(Lane lane, long steps, Map<String, Object> bindings) {
        return new Profile(lane, steps, Duration.ofMillis(250), 0, 16_384,
            StarlarkSemantics.DEFAULT, bindings, false, label -> standardOr(label, Profile::colonyLibrary), SILENT);
    }

    private static ResourceLocation standardOr(String label, Function<String, ResourceLocation> fallback) {
        return label.startsWith("@std//") ? ScriptStd.resolve(label) : fallback.apply(label);
    }

    private static ResourceLocation colonyLibrary(String label) {
        if (!label.startsWith("//scripts/")) {
            return null;
        }
        String name = label.substring("//scripts/".length());
        return ColonyScripts.validName(name)
            ? ResourceLocation.fromNamespaceAndPath("folkways_core", "scripts/" + name) : null;
    }

    public static final int MAX_PATTERN_SOURCE = 131_072;

    public static Profile draftPattern(Duration time, Map<String, Object> vocabulary,
            Function<String, ResourceLocation> resolver, BiConsumer<ResourceLocation, String> print) {
        return new Profile(Lane.DRAFT_PATTERN, 200_000, time, 0, MAX_PATTERN_SOURCE,
            StarlarkSemantics.builder().setBool("-allow_recursion", true).build(), vocabulary, false,
            label -> standardOr(label, resolver), print);
    }

    public Profile withLimits(long steps, Duration time, int calls) {
        return new Profile(lane, steps, time, calls, maxSource, semantics, predeclared, autoIndent,
            libraryResolver, print, allocationBytes, cumulativeAllocationBytes);
    }

    public Profile withBindings(Map<String, Object> bindings) {
        return new Profile(lane, steps, time, calls, maxSource, semantics, bindings, autoIndent,
            libraryResolver, print, allocationBytes, cumulativeAllocationBytes);
    }

    public Profile withLibraryResolver(Function<String, ResourceLocation> resolver) {
        return new Profile(lane, steps, time, calls, maxSource, semantics, predeclared, autoIndent,
            resolver, print, allocationBytes, cumulativeAllocationBytes);
    }

    public boolean allowsLoad() {
        return libraryResolver != null;
    }

    public int maxInFlight() {
        return lane.maxInFlight();
    }
}
