package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import static io.github.izakyl.folkways.assistant.tool.api.StateRegistry.segment;

public final class StateTools {

    private StateTools() {
    }

    public static void register(StateRegistry registry) {
        FacilityTools.register(registry);
        registry.register("residents/*/name", keys -> uuid(keys, 0), (keys, value) -> {
            JsonObject args = arguments("resident_id", keys.getFirst());
            args.add("name", value);
            return operation("rename_resident", args);
        }, null);
        registry.register("residents/*/work/*", keys -> {
            uuid(keys, 0);
            id(keys, 1);
        }, (keys, value) -> workOperation("set_work", "resident_id", keys, value), null, StateTools::mergeWork);
        registry.register("work_defaults/*/*", keys -> {
            id(keys, 0);
            id(keys, 1);
        }, (keys, value) -> workOperation("set_work_default", "resident_kind", keys, value), null, StateTools::mergeWork);
        for (String pattern : List.of("colony", "colony/problems", "residents/*", "residents/*/trace",
                "routines/*")) {
            registry.register(pattern, keys -> { }, null, null);
        }
        registry.registerCreation("surveys/*", keys -> { },
                (keys, value) -> operation("request_survey", object(value)), null);
        registry.registerCreation("builds/*", keys -> { }, (keys, value) -> {
            JsonObject args = object(value);
            JsonElement dimension = args.remove("dimension");
            if (dimension == null) {
                throw new ActionRegistry.Refused("invalid_arguments");
            }
            JsonObject at = args.has("at") ? object(args.get("at")) : new JsonObject();
            if (at.has("dimension") || (!args.has("at") && !args.has("points"))) {
                throw new ActionRegistry.Refused("invalid_arguments");
            }
            at.add("dimension", dimension);
            args.add("at", at);
            if (args.get("mirror") instanceof JsonPrimitive mirror && mirror.isBoolean()) {
                args.addProperty("mirror", mirror.getAsBoolean() ? "front_back" : "none");
            }
            return operation("commission_build", args);
        }, (keys, value) -> operation("cancel_build", arguments("id", keys.getFirst())));
        var actions = registry.operations();
        var write = ToolRegistry.spec("set_script", "Write a colony script library.",
                ActionTools.fields("name:string", "source:string"), "name", "source");
        var delete = ToolRegistry.spec("delete_script", "Delete a colony script library.",
                ActionTools.fields("name:string"), "name");
        actions.registerInternal(new ActionRegistry.Spec(write, "settings", false, true), StateTools::script);
        actions.registerInternal(new ActionRegistry.Spec(delete, "settings", true), StateTools::script);
        registry.register("scripts/*", keys -> {
            if (!ColonyScripts.validName(keys.getFirst())) {
                throw new ActionRegistry.Refused("invalid_arguments");
            }
        }, (keys, value) -> {
            JsonObject args = arguments("name", keys.getFirst());
            args.add("source", value);
            return operation("set_script", args);
        }, (keys, value) -> operation("delete_script", arguments("name", keys.getFirst())));
        registry.captureWith(StateTools::capture);
        registry.captureReplies(StateTools::captureReplies);
    }

    private static ActionRegistry.Plan script(ToolContext context, JsonObject args) {
        String name = args.get("name").getAsString();
        String source = args.has("source") ? args.get("source").getAsString() : null;
        if (source != null && source.length() > 8192) {
            throw new ActionRegistry.Refused("script_size_cap");
        }
        String before = ColonyScripts.snapshot(context.colony()).get(name);
        var summary = Component.translatable(source == null ? "folkways.tool.delete_script"
                : "folkways.tool.set_script", name);
        return new ActionRegistry.Plan(summary, args, before == null ? "null" : new JsonPrimitive(before).toString(),
                () -> {
                    ColonyScripts.write(context.colony(), name, source);
                    return CompletableFuture.completedFuture(new ToolReply(args, List.of()));
                });
    }

    private static void captureReplies(ToolContext context, Map<String, ToolReply> values) {
        JsonObject empty = new JsonObject();
        values.put("colony", ReadTools.overview(context, empty));
        values.put("colony/problems", ReadTools.problems(context, empty));
        for (var resident : context.colony().residents()) {
            String id = resident.id().toString();
            String path = "residents/" + id;
            JsonObject args = arguments("resident_id", id);
            values.put(path, ReadTools.resident(context, args));
            values.put(path + "/trace", PlanReads.read(context, args, "trace", false));
        }
        values.values().forEach(reply -> {
            reply.data().addProperty("colony_id", context.colony().id().toString());
            if (!reply.data().has("capture_tick")) {
                reply.data().addProperty("capture_tick", context.player().serverLevel().getGameTime());
            }
        });
    }

    private static StateRegistry.Operation workOperation(String name, String target, List<String> keys,
            JsonElement value) {
        JsonObject args = object(value);
        if (args.keySet().stream().anyMatch(key -> !key.equals("allowed") && !key.equals("keenness"))) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
        if (args.isEmpty()) {
            throw new ActionRegistry.Refused("missing_work_change");
        }
        args.addProperty(target, keys.get(0));
        args.addProperty("vocation", keys.get(1));
        return operation(name, args);
    }

    private static JsonElement mergeWork(JsonElement before, JsonElement after) {
        if (before instanceof JsonObject work) {
            JsonObject merged = work.deepCopy();
            after.getAsJsonObject().entrySet().forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
            return merged;
        }
        return after;
    }

    private static StateRegistry.Operation operation(String action, JsonObject args) {
        return new StateRegistry.Operation(action, args);
    }

    static JsonObject object(JsonElement value) {
        if (!value.isJsonObject()) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
        return value.getAsJsonObject().deepCopy();
    }

    private static JsonObject arguments(String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty(key, value);
        return args;
    }

    private static void uuid(List<String> keys, int index) {
        String key = keys.get(index);
        if (!UUID.fromString(key).toString().equals(key)) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
    }

    private static void id(List<String> keys, int index) {
        String key = keys.get(index);
        if (!ResourceLocation.parse(key).toString().equals(key)) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
    }

    private static void capture(ToolContext context, Map<String, JsonElement> values, Map<String, String> aliases) {
        var colony = context.colony();
        var server = context.player().getServer();
        var vocations = Vocations.all();
        Residents.of(colony, server).forEach(body -> {
            String prefix = "residents/" + body.mob().getUUID();
            var name = body.mob().getCustomName();
            values.put(prefix + "/name", new JsonPrimitive(name == null ? "" : name.getString()));
            var licences = body.licences();
            for (var vocation : vocations) {
                licences.trade(vocation).ifPresent(trade -> values.put(prefix + "/work/" + segment(
                        vocation.id().toString()), work(licences.allows(trade), licences.keennessOf(trade).number())));
            }
        });
        ResidentKinds.ids().stream().filter(kind -> ResidentKinds.of(kind).orElseThrow().workDefaults())
                .forEach(kind -> {
                    var defaults = WorkDefaults.of(colony, kind);
                    for (var vocation : vocations) {
                        if (Participations.between(kind, Stake.vocation(vocation.id())) != Participation.NONE) {
                            var rule = defaults.of(vocation.id());
                            values.put("work_defaults/" + segment(kind.toString()) + "/"
                                    + segment(vocation.id().toString()),
                                    work(rule.allowed(), rule.priority().number()));
                        }
                    }
                });
        for (var survey : SurveyReads.surveys(context, new JsonObject()).data().getAsJsonArray("surveys")) {
            values.put("surveys/" + survey.getAsJsonObject().get("id").getAsString(), survey);
        }
        Commissioning.source().ifPresent(source -> source.orders(colony).forEach(order -> {
            JsonObject entry = arguments("id", order.id().toString());
            entry.addProperty("name", order.name());
            entry.addProperty("pattern", order.pattern());
            entry.addProperty("status", order.cancelling() ? "cancelling" : "active");
            JsonObject anchor = ToolText.position(order.anchor(), server);
            entry.add("anchor", anchor);
            if (anchor.has("dimension")) {
                entry.add("dimension", anchor.get("dimension"));
            }
            if (order.path()) {
                JsonArray points = new JsonArray();
                order.marks().forEach(point -> points.add(xyz(point)));
                entry.add("points", points);
            } else if (!order.marks().isEmpty()) {
                entry.add("at", xyz(order.marks().getFirst()));
                entry.add("to", xyz(order.marks().getLast()));
            }
            order.progress().ifPresent(progress -> {
                entry.addProperty("placed", progress.placed());
                entry.addProperty("total", progress.total());
            });
            values.put("builds/" + order.id(), entry);
        }));
        ColonyScripts.snapshot(colony).forEach((name, source) ->
                values.put("scripts/" + name, new JsonPrimitive(source)));
        for (var routine : Routines.library()) {
            JsonObject data = arguments("description", routine.description());
            data.add("args", routine.argumentSchema());
            JsonArray writes = new JsonArray();
            routine.writeTargets().forEach(writes::add);
            data.add("writes", writes);
            values.put("routines/" + segment(routine.id().toString()), data);
        }
    }

    private static JsonObject xyz(BlockPos point) {
        JsonObject entry = new JsonObject();
        entry.addProperty("x", point.getX());
        entry.addProperty("y", point.getY());
        entry.addProperty("z", point.getZ());
        return entry;
    }

    private static JsonObject work(boolean allowed, int keenness) {
        JsonObject result = new JsonObject();
        result.addProperty("allowed", allowed);
        result.addProperty("keenness", keenness);
        return result;
    }
}
