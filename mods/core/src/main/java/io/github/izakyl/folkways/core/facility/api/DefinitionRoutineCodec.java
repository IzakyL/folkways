package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.mapped;
import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.only;
import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.value;

import io.github.izakyl.folkways.core.facility.api.DefinitionCodec.Field;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

final class DefinitionRoutineCodec {

    private DefinitionRoutineCodec() {
    }

    static Map<String, Object> routineData(Routine.Use use) {
        var args = routineArguments(use);
        if (use.routine().equals(Routines.SCRIPT)) {
            return Map.of("source", args.remove("source"), "with", args);
        }
        return Map.of("use", use.routine().toString(), "with", args);
    }

    private static Map<String, Object> routineArguments(Routine.Use use) {
        Map<String, Object> arguments = new LinkedHashMap<>(mapped(use.with(), DefinitionCodec::value));
        arguments.putAll(mapped(use.references(), DefinitionRoutineCodec::argumentData));
        return arguments;
    }

    static Routine.Use routine(Field use) {
        Map<String, Value> arguments = new LinkedHashMap<>();
        Map<String, Routine.Argument> references = new LinkedHashMap<>();
        use.get("with", Map.of()).map(DefinitionRoutineCodec::routineArgument).forEach((name, argument) -> {
            if (argument instanceof Routine.Argument.Literal literal) {
                arguments.put(name, literal.value());
            } else {
                references.put(name, argument);
            }
        });
        if (use.has("source")) {
            if (use.has("use") || arguments.containsKey("source") || references.containsKey("source")) {
                throw new IllegalArgumentException(use.path() + ": source");
            }
            arguments.put("source", new Value.Text(use.get("source").string()));
            return new Routine.Use(Routines.SCRIPT, arguments, references);
        }
        ResourceLocation id = use.get("use").id();
        if (id.equals(Routines.SCRIPT)) {
            throw new IllegalArgumentException(use.path() + ": use");
        }
        return new Routine.Use(id, arguments, references);
    }

    static Routine.Argument routineArgument(Field field) {
        if (field.value() instanceof Map<?, ?> && field.has("param") && only(field, "param")) {
            return new Routine.Argument.Parameter(field.get("param").string());
        }
        if (field.value() instanceof List<?>) {
            var entries = field.list(DefinitionRoutineCodec::routineArgument);
            var array = new Routine.Argument.Array(entries);
            return entries.stream().allMatch(Routine.Argument.Literal.class::isInstance)
                ? new Routine.Argument.Literal(array.resolve(Map.of())) : array;
        }
        if (field.value() instanceof Map<?, ?> && !field.has("pulse") && !field.has("snbt")) {
            return new Routine.Argument.ObjectValue(field.map(DefinitionRoutineCodec::routineArgument));
        }
        return new Routine.Argument.Literal(value(field));
    }

    static Object argumentData(Routine.Argument argument) {
        return switch (argument) {
            case Routine.Argument.Literal literal -> value(literal.value());
            case Routine.Argument.Parameter parameter -> Map.of("param", parameter.name());
            case Routine.Argument.ObjectValue object -> mapped(object.entries(), DefinitionRoutineCodec::argumentData);
            case Routine.Argument.Array array -> array.entries().stream().map(DefinitionRoutineCodec::argumentData).toList();
        };
    }
}
