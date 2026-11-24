package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Node;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

final class WriteStages implements Routine {
    private final WriteAll writeAll = new WriteAll();

    @Override
    public com.google.gson.JsonObject argumentSchema() {
        return Routine.argumentsSchema(Map.of("stages", "array", "require", "object"));
    }

    @Override
    public String description() {
        return "Set and settle named signals in ordered stages.";
    }

    @Override
    public java.util.List<String> writeTargets() {
        return java.util.List.of("stages");
    }

    @Override
    public ResourceLocation id() {
        return Routines.WRITE_STAGES;
    }

    @Override
    public void validate(ServerLevel level, FacilityDefinition facility, Use use) {
        var settings = use.resolve(facility.parameters()).with();
        if (!facility.controls().keySet().containsAll(writes(settings))) {
            throw new IllegalArgumentException("stage writes an unknown control");
        }
    }

    private static double number(Value value) {
        if (!(value instanceof Value.Real real)) {
            throw new IllegalArgumentException("expected a number");
        }
        return real.value();
    }

    @Override
    public Set<String> writes(Map<String, Value> arguments) {
        if (!arguments.containsKey("stages")) {
            return arguments.keySet();
        }
        Set<String> names = new LinkedHashSet<>();
        stages(arguments).forEach(stage -> names.addAll(stage.writes().keySet()));
        return names;
    }

    @Override
    public boolean ready(ServerLevel level, FacilityDefinition facility, Map<String, Value> arguments) {
        var rig = facility.rig(level).orElseThrow();
        return writes(arguments).stream().allMatch(name -> rig.part(name).filter(part ->
            part.feedback().isPresent() || part.kind() instanceof Rig.Signalled(
                SignalType.Pulse ignored)).isPresent());
    }

    @Override
    public Call snapshot(ServerLevel level, Call call) {
        var arguments = call.arguments();
        var rig = call.rig().orElseThrow();
        for (var condition : object(arguments.get("require")).entrySet()) {
            if (rig.part(condition.getKey()).filter(part -> part.shows(condition.getValue())).isEmpty()) {
                throw new IllegalArgumentException("unexpected state of " + condition.getKey());
            }
        }
        return stage(call, call.id(), rig, arguments);
    }

    @Override
    public Optional<String> check(Call call) {
        try {
            plan(call);
            return Optional.empty();
        } catch (IllegalArgumentException invalid) {
            return Optional.of(invalid.getMessage());
        }
    }

    @Override
    public Optional<Refinement.Change> expand(Call call) {
        var stages = plan(call);
        List<Node> nodes = new ArrayList<>();
        List<Before> links = new ArrayList<>();
        UUID previous = null;
        for (int index = 0; index < stages.size(); index++) {
            var stage = stages.get(index);
            UUID end = index == stages.size() - 1 ? call.id() : UUID.randomUUID();
            Grown change = writeAll.expand(stage(stage, end, stage.rig().orElseThrow(), stage.arguments()))
                .orElseThrow().replacement();
            nodes.addAll(change.nodes());
            links.addAll(change.links());
            if (previous != null) {
                for (Node node : change.nodes()) {
                    links.add(new Before(previous, node.id()));
                }
            }
            previous = end;
        }
        if (nodes.isEmpty()) {
            nodes.add(Routines.await(call.id(), call.owner(), call.site(), List.of(), WriteAll.PATIENCE));
        }
        return Optional.of(Refinement.Change.of(new Grown(nodes, links)));
    }

    private List<Call> plan(Call call) {
        Rig rig = call.rig().orElseThrow();
        var settings = stages(call.arguments());
        List<Map<String, Value>> targets = settings.stream().map(stage -> targets(stage, call.arguments())).toList();
        List<Call> plan = new ArrayList<>();
        for (int index = 0; index < settings.size(); index++) {
            var stage = settings.get(index);
            Map<String, Value> writes = targets.get(index);
            var next = stage(call, call.id(), rig, writes);
            writeAll.check(next).ifPresent(problem -> {
                throw new IllegalArgumentException(problem);
            });
            if (shown(rig, writes) || (!stage.when().isEmpty() && !shown(rig, stage.when()))) {
                continue;
            }
            if (!stage.before().isEmpty()) {
                Map<String, Value> later = new LinkedHashMap<>();
                for (var future : targets.subList(index + 1, targets.size())) {
                    future.forEach((name, value) -> {
                        if (stage.before().contains(name)) {
                            later.putIfAbsent(name, value);
                        }
                    });
                }
                if (shown(rig, later)) {
                    continue;
                }
            }
            plan.add(next);
            rig = rig.predict(writes);
        }
        return plan;
    }

    private static boolean shown(Rig rig, Map<String, Value> values) {
        return values.entrySet().stream().allMatch(entry ->
            rig.part(entry.getKey()).filter(part -> part.shows(entry.getValue())).isPresent());
    }

    private static Map<String, Value> targets(Stage stage, Map<String, Value> arguments) {
        Map<String, Value> targets = new LinkedHashMap<>();
        stage.writes().forEach((name, setting) -> {
            Value value = setting;
            if (setting instanceof Value.Data) {
                var ref = DefinitionCodec.fields(setting);
                value = arguments.get(text(ref.get("arg")));
                if (ref.containsKey("add")) {
                    double adjusted = number(value) + number(ref.get("add"));
                    Value minimum = ref.get("min");
                    value = Value.of(minimum == null ? adjusted : Math.max(adjusted, number(minimum)));
                }
            }
            if (value == null) {
                throw new IllegalArgumentException("missing stage argument for " + name);
            }
            targets.put(name, value);
        });
        return targets;
    }

    private static List<Stage> stages(Map<String, Value> settings) {
        if (!settings.containsKey("stages")) {
            return List.of(new Stage(settings, List.of(), Map.of()));
        }
        if (!(settings.get("stages") instanceof Value.Sequence entries) || entries.values().isEmpty()) {
            throw new IllegalArgumentException("expected a nonempty list of stages");
        }
        return entries.values().stream().map(DefinitionCodec::fields).map(fields -> fields.containsKey("write")
            ? new Stage(DefinitionCodec.fields(fields.get("write")), names(fields.get("before")), object(fields.get("when")))
            : new Stage(fields, List.of(), Map.of())).toList();
    }

    private static Map<String, Value> object(Value value) {
        return value == null ? Map.of() : DefinitionCodec.fields(value);
    }

    private static List<String> names(Value value) {
        if (value == null || value instanceof Value.Vector vector && vector.values().isEmpty()) {
            return List.of();
        }
        if (!(value instanceof Value.Sequence sequence)) {
            throw new IllegalArgumentException("expected a list of parts");
        }
        return sequence.values().stream().map(WriteStages::text).toList();
    }

    private static String text(Value value) {
        if (!(value instanceof Value.Text text)) {
            throw new IllegalArgumentException("expected a name");
        }
        return text.value();
    }

    private static Call stage(Call call, UUID id, Rig rig, Map<String, Value> arguments) {
        return new Call(id, call.owner(), call.facility(), call.site(), call.parameters(),
            arguments, call.readings(), Optional.of(rig));
    }

    private record Stage(Map<String, Value> writes, List<String> before, Map<String, Value> when) {
    }
}
