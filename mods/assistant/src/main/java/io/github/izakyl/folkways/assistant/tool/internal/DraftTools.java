package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.base.persist.Writer;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.operation.SettingChanges;
import io.github.izakyl.folkways.core.script.api.Profile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class DraftTools {

    public static final int MAX_POINTS = 4096;
    static final int MAX_LENGTH = 32768;
    static final int MAX_EDGE = 1024;

    record Target(ServerLevel level, Commissioning.Source source, Commissioning.Request request) {
    }

    private DraftTools() {
    }

    static JsonObject fields(boolean dimension) {
        JsonObject fields = ActionTools.fields("pattern:string", "source:string");
        if (dimension) {
            fields.add("dimension", ActionTools.fields("dimension:string").get("dimension"));
        }
        fields.add("at", ActionTools.position(!dimension));
        fields.add("to", ActionTools.position(false));
        JsonObject points = new JsonObject();
        points.addProperty("type", "array");
        points.add("items", ActionTools.position(false));
        fields.add("points", points);
        JsonObject settings = new JsonObject();
        settings.addProperty("type", "object");
        settings.addProperty("additionalProperties", true);
        fields.add("settings", settings);
        return fields;
    }

    static ToolSpec spec() {
        return ToolRegistry.spec("draft", "Draw a survival pattern without filing or changing the world. "
                + "Provide exactly one of pattern (registered id) or source (full Starlark program defining draw(site)). "
                + "Use points (2..4096 XYZ points) or at/to (XYZ corners), never both. "
                + "Each axis spans at most 1024 blocks; path length at most 32768 blocks. "
                + "Only loaded targets; settings use the selected pattern's knob definitions. At most 4 calls per script. "
                + "Uses the pattern drafting timeout. See help('pattern').",
                fields(true), "dimension");
    }

    static Target target(ToolContext context, JsonObject args, boolean draft) {
        return target(context, args, draft, null);
    }

    static CompletableFuture<Target> targetAsync(ToolContext context, JsonObject args, boolean draft) {
        require(args.has("pattern") != args.has("source"), "invalid_arguments");
        if (!args.has("source")) {
            return CompletableFuture.completedFuture(target(context, args, draft));
        }
        String written = args.get("source").getAsString();
        require(written.length() <= Profile.MAX_PATTERN_SOURCE, "pattern_source_too_large");
        var source = Commissioning.source().orElseThrow(() -> new ActionRegistry.Refused("building_unavailable"));
        return source.inline(context.player().serverLevel(), written)
                .thenApply(inline -> target(context, args, draft, inline));
    }

    private static Target target(ToolContext context, JsonObject args, boolean draft, Commissioning.Inline inline) {
        try {
            return request(context, args, draft, inline);
        } catch (ActionRegistry.Refused refusal) {
            String code = refusal.getMessage();
            throw new ActionRegistry.Refused(code, Commissioning.refusal(code).orElse(null));
        }
    }

    private static Target request(ToolContext context, JsonObject args, boolean draft, Commissioning.Inline inline) {
        require(args.has("pattern") != args.has("source") && args.has("source") == (inline != null),
                "invalid_arguments");
        String dimension = draft ? args.get("dimension").getAsString()
                : args.getAsJsonObject("at").get("dimension").getAsString();
        ServerLevel level = context.player().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                id(dimension)));
        require(level != null, "unloaded");
        Optional<List<BlockPos>> points = Optional.empty();
        BlockPos from;
        BlockPos to;
        if (args.has("points")) {
            require(!args.has("to"), "conflicting_marks");
            require(draft ? !args.has("at") : args.getAsJsonObject("at").size() == 1, "conflicting_marks");
            var array = args.getAsJsonArray("points");
            require(array.size() >= 2 && array.size() <= MAX_POINTS, "invalid_path_size");
            List<BlockPos> path = new ArrayList<>();
            long length = 0;
            for (var value : array) {
                BlockPos at = ActionTools.pos(value.getAsJsonObject());
                if (!path.isEmpty()) {
                    BlockPos previous = path.getLast();
                    length += Math.max(Math.abs((long) at.getX() - previous.getX()),
                            Math.max(Math.abs((long) at.getY() - previous.getY()),
                                    Math.abs((long) at.getZ() - previous.getZ())));
                    require(length <= MAX_LENGTH, "path_too_long");
                }
                path.add(at);
            }
            from = path.getFirst();
            to = path.getLast();
            var bounds = BoundingBox.encapsulatingPositions(path).orElseThrow();
            require(bounds.getXSpan() <= MAX_EDGE && bounds.getYSpan() <= MAX_EDGE
                    && bounds.getZSpan() <= MAX_EDGE, "region_too_large");
            path.forEach(point -> loaded(level, point));
            for (int index = 1; index < path.size(); index++) {
                BlockPos start = path.get(index - 1);
                BlockPos end = path.get(index);
                int steps = Math.max(Math.abs(end.getX() - start.getX()),
                        Math.max(Math.abs(end.getY() - start.getY()), Math.abs(end.getZ() - start.getZ())));
                for (int step = 0; step <= steps; step++) {
                    double along = steps == 0 ? 0 : (double) step / steps;
                    loaded(level, BlockPos.containing(start.getX() + (end.getX() - start.getX()) * along,
                            start.getY() + (end.getY() - start.getY()) * along,
                            start.getZ() + (end.getZ() - start.getZ()) * along));
                }
            }
            points = Optional.of(List.copyOf(path));
        } else {
            require(args.has("at"), "missing_mark");
            from = ActionTools.pos(args.getAsJsonObject("at"));
            to = args.has("to") ? ActionTools.pos(args.getAsJsonObject("to")) : from;
            loaded(level, from);
            loaded(level, to);
            ActionTools.region(level, from, to, 32768);
        }
        int rotation = args.has("rotation") ? ActionTools.integer(args.get("rotation")) : 0;
        Direction facing = switch (rotation) {
            case 0 -> Direction.NORTH;
            case 90 -> Direction.EAST;
            case 180 -> Direction.SOUTH;
            case 270 -> Direction.WEST;
            default -> throw new ActionRegistry.Refused("invalid_rotation");
        };
        var source = inline == null
                ? Commissioning.source().orElseThrow(() -> new ActionRegistry.Refused("building_unavailable"))
                : inline.source();
        var id = inline == null ? id(args.get("pattern").getAsString()) : inline.id();
        var schema = inline == null
                ? source.descriptions().stream().filter(pattern -> pattern.id().equals(id)).findFirst()
                    .orElseThrow(() -> new ActionRegistry.Refused("unknown_pattern_or_mark")).knobs()
                : inline.knobs();
        Writer settings = Writer.of();
        if (args.has("settings")) {
            for (var entry : args.getAsJsonObject("settings").entrySet()) {
                Schema.Setting declared = schema.find(entry.getKey())
                        .orElseThrow(() -> new ActionRegistry.Refused("unknown_setting"));
                var valid = SettingChanges.validated(declared, ActionTools.value(declared, entry.getValue()))
                        .orElseThrow(() -> new ActionRegistry.Refused("invalid_setting_value"));
                switch (valid) {
                    case ColonySettings.Value.Flag flag -> settings.flag(entry.getKey(), flag.value());
                    case ColonySettings.Value.Count count -> settings.integer(entry.getKey(), count.value());
                    case ColonySettings.Value.Choice choice -> settings.id(entry.getKey(), choice.value());
                    case ColonySettings.Value.Items items -> settings.blob(entry.getKey(), ColonySettings.empty()
                            .with(entry.getKey(), items).items(entry.getKey())
                            .map(spec -> spec.save()).orElseGet(CompoundTag::new));
                }
            }
        }
        var request = new Commissioning.Request(id, from, to, facing,
                args.has("mirror") ? args.get("mirror").getAsString() : "none", points, settings.tag());
        source.refusal(level, context.colony(), request).ifPresent(code -> {
            throw new ActionRegistry.Refused(code);
        });
        return new Target(level, source, request);
    }

    private static ResourceLocation id(String written) {
        ResourceLocation id = ResourceLocation.tryParse(written);
        require(id != null, "invalid_arguments");
        return id;
    }

    static void loaded(ServerLevel level, BlockPos at) {
        ActionTools.loaded(level, at);
        require(level.getWorldBorder().isWithinBounds(at), "outside_world");
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw new ActionRegistry.Refused(code);
        }
    }

    public static CompletableFuture<ToolReply> draft(ServerPlayer player, JsonObject args) {
        try {
            var colony = CurrentColony.of(player).orElseThrow(() -> new ActionRegistry.Refused("not_member"));
            return targetAsync(new ToolContext(player, colony), args, true).thenCompose(target -> {
                require(CurrentColony.of(player).filter(current -> current.id().equals(colony.id())).isPresent(),
                        "not_member");
                return target.source().draw(target.level(), colony, target.request());
            }).thenApply(drawing -> {
                require(CurrentColony.of(player).filter(current -> current.id().equals(colony.id())).isPresent(),
                        "not_member");
                ToolReply reply = reply(drawing);
                reply.data().addProperty("colony_id", colony.id().toString());
                reply.data().addProperty("capture_tick", player.serverLevel().getGameTime());
                return reply.capped(player.registryAccess());
            }).exceptionally(failure -> {
                Throwable cause = failure;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                if (cause instanceof Commissioning.PatternFailure invalid) {
                    ToolReply reply = reply(new Commissioning.Drawing.Refused(invalid.code(), invalid.refusal(),
                            invalid.getMessage()));
                    return reply.capped(player.registryAccess());
                }
                return error(failure);
            });
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(error(invalid));
        }
    }

    static ToolReply error(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return ActionRegistry.error(failure);
    }

    static ToolReply reply(Commissioning.Drawing drawing) {
        ToolText text = new ToolText();
        JsonObject data = new JsonObject();
        data.addProperty("ok", drawing instanceof Commissioning.Drawing.Ready);
        data.add("bounds", JsonNull.INSTANCE);
        data.addProperty("blocks", 0);
        data.add("materials", new JsonArray());
        data.addProperty("joints", 0);
        if (drawing instanceof Commissioning.Drawing.Refused refused) {
            data.addProperty("code", refused.code());
            data.add("refusal", text.text(refused.refusal()));
            if (!refused.diagnostic().isEmpty()) {
                data.addProperty("diagnostic", refused.diagnostic());
            }
        } else {
            var ready = (Commissioning.Drawing.Ready) drawing;
            JsonObject bounds = new JsonObject();
            bounds.add("from", cell(ready.from()));
            bounds.add("to", cell(ready.to()));
            data.add("bounds", bounds);
            data.addProperty("blocks", ready.blocks());
            JsonArray materials = new JsonArray();
            ready.materials().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> materials.add(text.ware(entry.getKey().toString(), entry.getValue())));
            data.add("materials", materials);
            data.addProperty("joints", ready.joints());
            JsonArray anchors = new JsonArray();
            ready.anchors().forEach(at -> anchors.add(cell(at)));
            data.add("anchors", anchors);
            ready.railConnections().ifPresent(connections -> {
                JsonObject rail = new JsonObject();
                rail.addProperty("start", connections.start());
                rail.addProperty("end", connections.end());
                rail.addProperty("connected", connections.connected());
                rail.addProperty("endpoints", 2);
                rail.addProperty("nearby", connections.nearby());
                data.add("rail_connections", rail);
            });
        }
        return text.reply(data);
    }

    private static JsonObject cell(BlockPos pos) {
        JsonObject cell = new JsonObject();
        cell.addProperty("x", pos.getX());
        cell.addProperty("y", pos.getY());
        cell.addProperty("z", pos.getZ());
        return cell;
    }

    static ToolReply patterns(ToolContext context, JsonObject args) {
        ToolText text = new ToolText();
        JsonArray patterns = new JsonArray();
        Commissioning.source().ifPresent(source -> source.descriptions().forEach(pattern -> {
            JsonObject row = new JsonObject();
            row.addProperty("id", pattern.id().toString());
            JsonArray accepts = new JsonArray();
            pattern.accepts().forEach(accepts::add);
            row.add("accepts", accepts);
            String mark = pattern.accepts().containsAll(List.of("zone", "path")) ? "ground_marks"
                    : pattern.accepts().contains("path") ? "ground_points" : "ground_corners";
            row.add("anchors", text.text(pattern.marking()
                    .orElseGet(() -> Component.translatable("folkways.assistant.pattern." + mark))));
            JsonArray knobs = text.schema(pattern.knobs());
            var defaults = ColonySettings.byDefault(pattern.knobs());
            for (var entry : knobs) {
                JsonObject knob = entry.getAsJsonObject();
                String key = knob.get("key").getAsString();
                knob.add("default", ToolText.value(defaults.valueOf(key).orElseThrow()));
                if (!knob.has("options")) {
                    JsonArray options = new JsonArray();
                    pattern.options().getOrDefault(key, List.of()).forEach(options::add);
                    knob.add("options", options);
                }
            }
            row.add("knobs", knobs);
            JsonObject errors = new JsonObject();
            pattern.errors().forEach((key, value) -> errors.add(key, text.text(value)));
            row.add("errors", errors);
            patterns.add(row);
        }));
        JsonObject data = new JsonObject();
        data.add("patterns", patterns);
        return text.reply(data);
    }
}
