package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.mapped;
import static io.github.izakyl.folkways.core.facility.api.DefinitionCodec.only;

import io.github.izakyl.folkways.core.facility.api.DefinitionCodec.Field;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

final class DefinitionTriggerCodec {

    private DefinitionTriggerCodec() {
    }

    static Map<String, Object> trigger(FacilityDefinition.Switch entry) {
        Map<String, Object> fields = switch (entry.trigger()) {
            case Trigger.Manual manual -> Map.of("kind", "manual", "routine", manual.routine());
            case Trigger.Condition condition -> {
                Map<String, Object> data = new LinkedHashMap<>(Map.of("kind", "condition",
                    "routine", condition.routine(),
                    "when", mapped(condition.when(), DefinitionCodec::value),
                    "period", condition.period(), "cooldown", condition.cooldown(),
                    "hysteresis", condition.hysteresis(), "enabled", entry.enabled()));
                condition.source().ifPresent(id -> data.put("source", id.toString()));
                condition.script().ifPresent(source -> data.put("when", source));
                yield Map.copyOf(data);
            }
            case Trigger.Schedule schedule -> Map.of("kind", "schedule", "routine", schedule.routine(),
                "dayTime", schedule.dayTime(), "enabled", entry.enabled());
            default -> throw new IllegalArgumentException("triggers");
        };
        Trigger.Retry retry = entry.trigger().retry();
        if (retry.equals(Trigger.Retry.DEFAULT)) {
            return fields;
        }
        var data = new LinkedHashMap<>(fields);
        data.put("retries", retry.retries());
        data.put("backoff", retry.backoff());
        return Map.copyOf(data);
    }

    static FacilityDefinition.Switch trigger(Field field) {
        String routine = field.get("routine").string();
        Field kind = field.get("kind");
        Field retries = field.get("retries", Trigger.Retry.DEFAULT.retries());
        Field backoff = field.get("backoff", Trigger.Retry.DEFAULT.backoff());
        if (retries.integer() < 0 || retries.integer() > Trigger.Retry.MAX_RETRIES) {
            throw retries.invalid();
        }
        if (backoff.integer() < 1 || backoff.integer() > Trigger.Retry.MAX_BACKOFF) {
            throw backoff.invalid();
        }
        var retry = new Trigger.Retry(retries.integer(), backoff.integer());
        return switch (kind.string()) {
            case "manual" -> new FacilityDefinition.Switch(new Trigger.Manual(routine, retry), true);
            case "condition" -> {
                if (!only(field, "kind", "routine", "when", "source", "period", "cooldown",
                        "hysteresis", "enabled", "retries", "backoff")) {
                    throw field.invalid();
                }
                Field predicate = field.get("when", Map.of());
                boolean scripted = predicate.value() instanceof String;
                Map<String, Value> when = scripted ? Map.of() : predicate.map(DefinitionCodec::value);
                Optional<ResourceLocation> source = field.has("source")
                    ? Optional.of(field.get("source").id()) : Optional.empty();
                Field period = field.get("period", 10);
                Field cooldown = field.get("cooldown", 0);
                Field hysteresis = field.get("hysteresis", 1);
                if (period.integer() < 10) {
                    throw period.invalid();
                }
                if (cooldown.integer() < 0) {
                    throw cooldown.invalid();
                }
                if (hysteresis.integer() < 1) {
                    throw hysteresis.invalid();
                }
                Optional<String> script = scripted ? Optional.of(predicate.string()) : Optional.empty();
                if (when.isEmpty() && source.isEmpty() && script.isEmpty()) {
                    throw field.get("when", Map.of()).invalid();
                }
                yield new FacilityDefinition.Switch(new Trigger.Condition(routine, when, source, period.integer(),
                    cooldown.integer(), hysteresis.integer(), script, retry), field.get("enabled").bool());
            }
            case "schedule" -> {
                Field time = field.get("dayTime");
                int ticks = time.integer();
                if (ticks < 0 || ticks >= 24000) {
                    throw time.invalid();
                }
                yield new FacilityDefinition.Switch(new Trigger.Schedule(routine, ticks, retry), field.get("enabled").bool());
            }
            default -> throw kind.invalid();
        };
    }
}
