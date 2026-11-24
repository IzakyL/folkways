package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Samples optional script hooks and publishes immutable observations for facility consumers. */
final class FacilitySamples {

    private static final Map<FacilityDefinition, Snapshot> PUBLISHED =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());

    private record Snapshot(Map<String, Value> readings, Map<FacilityScripts.Source, Boolean> predicates,
                            Map<String, Routine.Path> paths, Map<Integer, List<AABB>> keepouts) {
    }

    private record Job(String key, FacilityScripts.Compiled program, String hook, Map<String, Value> args,
                       Function<Object, Object> decode) {
    }

    private record Pending(Job job, CompletableFuture<Object> future, int tick) {
    }

    private final Map<String, Object> results = new HashMap<>();
    private final List<Pending> pending = new ArrayList<>();
    private List<Job> jobs;
    private Map<String, FacilityScripts.Source> sources;
    private long nextSample;
    private int cursor;

    static Optional<FacilityScripts.Source> predicate(Map<String, Value> values) {
        // The definition codec reserves the empty name to distinguish a script from reading comparisons.
        return values.get("") instanceof Value.Text source
            ? Optional.of(new FacilityScripts.Source(source.value(), "when")) : Optional.empty();
    }

    static Map<String, Value> readings(FacilityDefinition facility) {
        return PUBLISHED.getOrDefault(facility, EMPTY).readings();
    }

    static Optional<String> predicate(FacilityDefinition facility, FacilityScripts.Source source) {
        Boolean result = PUBLISHED.getOrDefault(facility, EMPTY).predicates().get(source);
        return result == null ? Optional.of("unknown") : result ? Optional.empty() : Optional.of("state");
    }

    static Optional<Routine.Path> path(FacilityDefinition facility, String routine) {
        return Optional.ofNullable(PUBLISHED.getOrDefault(facility, EMPTY).paths().get(routine));
    }

    static List<AABB> keepout(FacilityDefinition facility, int index) {
        return PUBLISHED.getOrDefault(facility, EMPTY).keepouts().getOrDefault(index, List.of());
    }

    static Map<String, FacilityScripts.Source> sources(FacilityDefinition facility) {
        Map<String, FacilityScripts.Source> scripts = new LinkedHashMap<>();
        facility.probes().forEach((name, probe) -> probe.source().ifPresent(source ->
            scripts.put("readings." + name, new FacilityScripts.Source(source, "read"))));
        for (int i = 0; i < facility.gates().size(); i++) {
            addPredicate(scripts, "projections[" + i + "].gate.open_when", facility.gates().get(i).openWhen());
        }
        for (int i = 0; i < facility.rides().size(); i++) {
            addPredicate(scripts, "projections[" + i + "].ride.available_when", facility.rides().get(i).availableWhen());
        }
        for (int i = 0; i < facility.keepouts().size(); i++) {
            var source = facility.keepouts().get(i).source();
            if (source.isPresent()) {
                scripts.put("keepouts." + i, new FacilityScripts.Source(source.get(), "keepout"));
            }
        }
        var elements = facility.card().elements();
        for (int i = 0; i < elements.size(); i++) {
            var when = switch (elements.get(i)) {
                case Card.Element.Button button -> button.enabledWhen();
                case Card.Element.Target target -> target.enabledWhen();
                case Card.Element.Choice choice -> choice.enabledWhen();
                default -> Map.<String, Value>of();
            };
            addPredicate(scripts, "ui.card[" + i + "].enabled_when", when);
        }
        return scripts;
    }

    private static void addPredicate(Map<String, FacilityScripts.Source> scripts, String key, Map<String, Value> when) {
        predicate(when).ifPresent(source -> scripts.put(key, source));
    }

    void unload(FacilityDefinition facility) {
        PUBLISHED.remove(facility);
        pending.forEach(job -> job.future().cancel(false));
        pending.clear();
        results.clear();
    }

    boolean tick(DefinedFacilities.Entry entry, ServerLevel level, Colony colony, long now) {
        if (jobs == null) {
            sources = sources(entry.facility);
            jobs = jobs(entry);
        }
        if (jobs.isEmpty()) {
            return false;
        }
        int tick = level.getServer().getTickCount();
        boolean failed = false;
        boolean changed = false;
        var iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending work = iterator.next();
            if (work.tick() == tick || !work.future().isDone()) {
                continue;
            }
            iterator.remove();
            String key = work.job().key();
            try {
                results.put(key, ScriptRuntime.result(work.future()));
            } catch (ScriptFault fault) {
                entry.pausedSamples.add(key);
                results.remove(key);
                entry.recent = new DefinedFacilities.Result(key, now, "failed",
                    Optional.of("folkways.facility.script." + FacilityScripts.code(fault)));
                failed = true;
            }
            changed = true;
        }
        if (changed) {
            publish(entry.facility);
        }
        if (now < nextSample || !pending.isEmpty()) {
            return failed;
        }
        nextSample = now + 10;
        if (jobs.stream().allMatch(job -> entry.pausedSamples.contains(job.key()))) {
            return failed;
        }
        var readings = Map.copyOf(Readings.snapshot(level, entry.facility.readings(level)));
        var frame = FacilityScriptRuns.frame(level, entry.facility);
        Map<String, Value> input = new LinkedHashMap<>(entry.facility.parameters());
        input.putAll(readings);
        frame.ifPresent(value -> input.put("_frame", value));
        var snapshot = Map.copyOf(input);
        var libraries = ColonyScripts.snapshot(colony);
        Map<FacilityScripts.Compiled, FacilityScriptContexts.Taken> shots = new HashMap<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(cursor);
            if (entry.pausedSamples.contains(job.key())) {
                cursor = (cursor + 1) % jobs.size();
                continue;
            }
            var colonyShots = shots.computeIfAbsent(job.program(), program ->
                FacilityScriptRuns.shots(level, colony, entry.facility, program));
            var future = FacilityScripts.submit(!job.program().source().function().equals("step"),
                () -> job.program().hook(job.hook(), snapshot, job.args(), libraries, job.decode(), colonyShots));
            if (future == null) {
                break;
            }
            pending.add(new Pending(job, future, tick));
            cursor = (cursor + 1) % jobs.size();
        }
        return failed;
    }

    private List<Job> jobs(DefinedFacilities.Entry entry) {
        List<Job> samples = new ArrayList<>();
        Set<FacilityScripts.Source> predicates = new HashSet<>();
        sources.forEach((key, source) -> {
            var program = entry.programs.get(source);
            if (program == null || source.function().equals("when") && !predicates.add(source)) {
                return;
            }
            Function<Object, Object> decode = switch (source.function()) {
                case "when" -> FacilitySamples::bool;
                case "keepout" -> FacilitySamples::boxes;
                default -> data -> data == null ? Optional.empty() : Optional.of(DefinitionCodec.argument(data));
            };
            samples.add(new Job(key, program, source.function(), entry.facility.parameters(), decode));
        });
        entry.facility.routines().forEach((name, use) -> {
            var program = FacilityScriptRuns.script(entry, name);
            if (program == null) {
                return;
            }
            var args = use.resolve(entry.facility.parameters()).arguments(Map.of());
            args.remove("source");
            var options = Map.copyOf(args);
            if (program.program().definesFunction("readings")) {
                samples.add(new Job("routines." + name + ".readings", program, "readings", options,
                    data -> new DefinitionCodec.Field("readings", data).map(DefinitionCodec::value)));
            }
            if (program.program().definesFunction("path")) {
                samples.add(new Job("routines." + name + ".path", program, "path", options, FacilitySamples::line));
            }
        });
        return List.copyOf(samples);
    }

    private void publish(FacilityDefinition facility) {
        Map<String, Value> readings = new LinkedHashMap<>();
        Map<String, Routine.Path> paths = new HashMap<>();
        Map<FacilityScripts.Source, Boolean> predicates = new HashMap<>();
        Map<Integer, List<AABB>> keepouts = new HashMap<>();
        for (Job job : jobs) {
            Object result = results.get(job.key());
            if (result == null) {
                continue;
            }
            String key = job.key();
            switch (job.hook()) {
                case "when" -> predicates.put(sources.get(key), (Boolean) result);
                case "read" -> ((Optional<?>) result).ifPresent(value -> readings.put(key.substring(9), (Value) value));
                case "path" -> ((Optional<?>) result).ifPresent(path ->
                    paths.put(key.substring(9, key.length() - 5), (Routine.Path) path));
                case "keepout" -> {
                    @SuppressWarnings("unchecked")
                    var boxes = (List<AABB>) result;
                    keepouts.put(Integer.parseInt(key.substring(9)), boxes);
                }
                case "readings" -> ((Map<?, ?>) result).forEach((name, value) ->
                    readings.put(key.substring(9, key.length() - 8) + name, (Value) value));
                default -> throw new IllegalStateException(job.hook());
            }
        }
        PUBLISHED.put(facility, new Snapshot(Map.copyOf(readings), Map.copyOf(predicates), Map.copyOf(paths),
            Map.copyOf(keepouts)));
    }

    static boolean bool(Object data) {
        return new DefinitionCodec.Field("boolean", data).bool();
    }

    // A list of points, or {"points": [...], "mark": bool, "strike": bool}.
    private static Optional<Routine.Path> line(Object data) {
        if (data == null) {
            return Optional.empty();
        }
        var field = new DefinitionCodec.Field("path", data);
        boolean mark = true;
        boolean strike = false;
        if (data instanceof Map<?, ?>) {
            mark = !field.has("mark") || field.get("mark").bool();
            strike = field.has("strike") && field.get("strike").bool();
            field = field.get("points");
        }
        var points = field.list(point -> {
            var values = point.list(DefinitionCodec.Field::number);
            if (values.size() != 3) {
                throw point.invalid();
            }
            return new Vec3(values.get(0), values.get(1), values.get(2));
        });
        return points.isEmpty() ? Optional.empty() : Optional.of(new Routine.Path(points, mark, strike));
    }

    private static List<AABB> boxes(Object data) {
        return new DefinitionCodec.Field("keepout", data).list(box -> {
            var axes = box.list(DefinitionCodec.Field::number);
            if (axes.size() != 6) {
                throw box.invalid();
            }
            return new AABB(axes.get(0), axes.get(1), axes.get(2),
                axes.get(3), axes.get(4), axes.get(5));
        });
    }
}
