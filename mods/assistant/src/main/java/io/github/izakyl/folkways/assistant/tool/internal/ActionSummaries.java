package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.Operating;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.front.engine.operation.SettingChanges;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

// Only words and effective values belong on a confirmation; transport arguments stay with the tool.
final class ActionSummaries {

    private ActionSummaries() {
    }

    static Component of(ToolContext context, String tool, JsonObject args) {
        return switch (tool) {
            case "rename_resident" -> Component.translatable("folkways.assistant.preview.rename",
                    resident(context, args),
                    args.get("name").getAsString().isEmpty()
                            ? Component.translatable("folkways.assistant.preview.original_name")
                            : Component.literal(args.get("name").getAsString()));
            case "set_setting" -> setting(context, args);
            case "set_work", "set_work_default" -> work(context, args, tool.equals("set_work_default"));
            case "create_zone" -> Component.translatable("folkways.assistant.preview.zone", kind(args),
                    dimension(args.get("dimension").getAsString()), cell(args.getAsJsonObject("from")),
                    cell(args.getAsJsonObject("to")));
            case "create_path" -> path(args);
            case "request_survey" -> Component.translatable("folkways.assistant.preview.survey",
                    dimension(args.get("dimension").getAsString()), points(args));
            case "erase_zone_or_path" -> Component.translatable("folkways.assistant.preview.erase",
                    holding(context, UUID.fromString(args.get("id").getAsString())));
            case "commission_build" -> {
                JsonObject at = args.getAsJsonObject("at");
                JsonObject to = args.has("to") ? args.getAsJsonObject("to") : at;
                if (args.has("points")) {
                    var points = args.getAsJsonArray("points");
                    at = points.get(0).getAsJsonObject();
                    to = points.get(points.size() - 1).getAsJsonObject();
                }
                String dimension = args.getAsJsonObject("at").get("dimension").getAsString();
                Component pattern = args.has("source") ? Component.translatable("folkways.assistant.preview.inline_pattern")
                        : named("pattern", args, "pattern");
                if (args.has("points")) {
                    yield Component.translatable("folkways.assistant.preview.build_path", pattern,
                            dimension(dimension), cell(at), cell(to));
                }
                yield Component.translatable("folkways.assistant.preview.build", pattern,
                        dimension(dimension), cell(at),
                        cell(to),
                        args.has("rotation") ? args.get("rotation").getAsInt() : 0);
            }
            default -> throw new IllegalArgumentException("Missing action summary: " + tool);
        };
    }

    static Component subject(ToolContext context, ActionBatch.Intent intent, ToolReply preview) {
        var args = intent.arguments();
        return switch (intent.action()) {
            case "rename_resident", "set_work" -> resident(context, args);
            case "set_facility", "delete_facility" -> Component.literal(args.get("name").getAsString());
            case "run", "cancel" -> facility(context, args.get("facility").getAsString());
            case "set_facility_parameter" -> facility(context, args.get("id").getAsString());
            case "erase_zone_or_path" -> holding(context, UUID.fromString(args.get("id").getAsString()));
            case "set_setting" -> args.has("zone_or_path")
                    ? holding(context, UUID.fromString(args.get("zone_or_path").getAsString())) : settings();
            case "set_work_default", "set_script", "delete_script" -> settings();
            case "create_zone", "create_path" -> intent.path() == null ? kind(args) : stagedName(intent);
            case "request_survey" -> intent.path() == null ? preview.component("summary") : stagedName(intent);
            case "commission_build" -> intent.path() != null ? stagedName(intent)
                    : args.has("source") ? Component.translatable("folkways.assistant.preview.inline_pattern")
                            : named("pattern", args, "pattern");
            case "cancel_build" -> Commissioning.source().stream().flatMap(source -> source.orders(context.colony()).stream())
                    .filter(order -> order.id().toString().equals(args.get("id").getAsString()))
                    .findFirst().<Component>map(order -> Component.literal(order.name()))
                    .orElseGet(() -> preview.component("summary"));
            default -> preview.component("summary");
        };
    }

    private static Component stagedName(ActionBatch.Intent intent) {
        return Component.literal(StateRegistry.decode(intent.path().substring(intent.path().indexOf('/') + 1)));
    }

    private static Component facility(ToolContext context, String id) {
        return FacilityTools.find(context, id).map(Operating::title).orElseGet(() -> Component.literal(id));
    }

    private static Component settings() {
        return Component.translatable("folkways.assistant.settings");
    }

    static Component withParameters(Component summary, JsonObject args) {
        if (!args.has("parameters") || args.getAsJsonObject("parameters").isEmpty()) {
            return summary;
        }
        return summary.copy().append("\n").append(Component.translatable(
                "folkways.assistant.preview.drawing_parameters", args.get("parameters").toString()));
    }

    static Component drawing(Commissioning.Drawing.Ready ready) {
        var materials = Component.empty();
        ready.materials().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!materials.getSiblings().isEmpty()) {
                materials.append(", ");
            }
            materials.append(Component.translatable(BuiltInRegistries.ITEM.get(entry.getKey()).getDescriptionId()))
                    .append(" × " + entry.getValue());
        });
        var summary = Component.translatable("folkways.assistant.preview.draft", ready.from().toShortString(),
                ready.to().toShortString(), ready.blocks(), materials, ready.joints());
        ready.railConnections().ifPresent(connections -> {
            summary.append("\n").append(Component.translatable("folkways.assistant.preview.rail_connections",
                    connections.connected(), 2));
            if (connections.nearby() && connections.connected() == 0) {
                summary.append("\n").append(Component.translatable("folkways.assistant.preview.rail_disconnected"));
            }
        });
        return summary;
    }

    static Component parameter(Operating operating, String key, JsonElement value) {
        return Component.translatable("folkways.assistant.preview.setting", key, operating.title(), value.toString());
    }

    private static Component setting(ToolContext context, JsonObject args) {
        Optional<UUID> drawn = args.has("zone_or_path")
                ? Optional.of(UUID.fromString(args.get("zone_or_path").getAsString())) : Optional.empty();
        var schema = SettingChanges.schema(context.colony(), ResourceLocation.parse(args.get("scope").getAsString()),
                drawn).find(args.get("key").getAsString()).orElseThrow();
        var server = context.player().getServer();
        Component target = drawn.map(id -> holding(context, id))
                .orElseGet(() -> Charters.title(server, Charters.of(server, context.colony())));
        return Component.translatable("folkways.assistant.preview.setting", Component.translatable(schema.nameKey()),
                target, ToolText.valueWords(ActionTools.value(schema, args.get("effective_value"))));
    }

    private static Component work(ToolContext context, JsonObject args, boolean defaults) {
        var vocation = Vocations.of(ResourceLocation.parse(args.get("vocation").getAsString())).orElseThrow();
        boolean allowed;
        int keenness;
        Component who;
        if (defaults) {
            ResourceLocation kind = ResourceLocation.parse(args.get("resident_kind").getAsString());
            var rule = WorkDefaults.of(context.colony(), kind).of(vocation.id());
            allowed = rule.allowed();
            keenness = rule.priority().number();
            who = named("resident.kind", args, "resident_kind");
        } else {
            var body = Residents.of(context.colony(), context.player().getServer(),
                    UUID.fromString(args.get("resident_id").getAsString())).orElseThrow();
            var trade = body.licences().trade(vocation).orElseThrow();
            allowed = body.licences().allows(trade);
            keenness = body.licences().keennessOf(trade).number();
            who = body.mob().getName();
        }
        boolean wasAllowed = allowed;
        int wasKeenness = keenness;
        if (args.has("allowed")) {
            allowed = args.get("allowed").getAsBoolean();
        }
        if (args.has("keenness")) {
            keenness = args.get("keenness").getAsInt();
        }
        Component vocationName = named("vocation", args, "vocation");
        if (allowed == wasAllowed && keenness == wasKeenness) {
            return Component.translatable(
                    defaults ? "folkways.assistant.preview.work_default" : "folkways.assistant.preview.work",
                    who, vocationName, ToolText.valueWords(new ColonySettings.Value.Flag(allowed)), keenness);
        }
        return Component.translatable(
                defaults ? "folkways.assistant.preview.work_default_change" : "folkways.assistant.preview.work_change",
                who, vocationName, ToolText.valueWords(new ColonySettings.Value.Flag(wasAllowed)), wasKeenness,
                ToolText.valueWords(new ColonySettings.Value.Flag(allowed)), keenness);
    }

    private static Component resident(ToolContext context, JsonObject args) {
        return Residents.of(context.colony(), context.player().getServer(),
                UUID.fromString(args.get("resident_id").getAsString())).orElseThrow().mob().getName();
    }

    private static Component holding(ToolContext context, UUID id) {
        var holding = context.colony().claim(id).orElseThrow();
        var words = name("delegation", holding.owner()).copy();
        holding.what().cells().stream().findFirst()
                .flatMap(at -> WorldSpaces.resolve(context.player().getServer(), at)).ifPresent(at ->
                        words.append(" · ").append(dimension(at.level().dimension().location().toString()))
                                .append(" ").append(Component.literal(at.cell().toShortString())));
        return words;
    }

    private static Component path(JsonObject args) {
        return Component.translatable("folkways.assistant.preview.path", kind(args),
                dimension(args.get("dimension").getAsString()), points(args));
    }

    private static Component points(JsonObject args) {
        var points = Component.empty();
        for (var point : args.getAsJsonArray("points")) {
            if (!points.getSiblings().isEmpty()) {
                points.append(" → ");
            }
            points.append(cell(point.getAsJsonObject()));
        }
        return points;
    }

    private static Component cell(JsonObject at) {
        return Component.literal(new BlockPos(at.get("x").getAsInt(), at.get("y").getAsInt(),
                at.get("z").getAsInt()).toShortString());
    }

    private static Component kind(JsonObject args) {
        return named("delegation", args, "kind");
    }

    private static Component dimension(String id) {
        return name("dimension", ResourceLocation.parse(id));
    }

    private static Component named(String category, JsonObject args, String key) {
        return name(category, ResourceLocation.parse(args.get(key).getAsString()));
    }

    private static Component name(String category, ResourceLocation id) {
        String key = category.equals("dimension") ? category : "folkways." + category;
        return Component.translatableWithFallback(key + "." + id.getNamespace() + "." + id.getPath(),
                id.getPath().replace('_', ' ').replace('/', ' '));
    }
}
