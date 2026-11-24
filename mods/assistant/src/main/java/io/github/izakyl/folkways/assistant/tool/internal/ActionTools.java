package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.core.front.engine.operation.ResidentChanges;
import io.github.izakyl.folkways.core.front.engine.operation.SettingChanges;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.resident.api.Participation;
import io.github.izakyl.folkways.core.resident.api.Participations;
import io.github.izakyl.folkways.core.resident.api.ResidentKinds;
import io.github.izakyl.folkways.core.resident.api.Stake;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class ActionTools {

    private ActionTools() {
    }

    public static ActionRegistry create() {
        ActionRegistry registry = new ActionRegistry();
        register(registry);
        return registry;
    }

    public static void register(ActionRegistry registry) {
        JsonObject setting = fields("scope:string", "key:string", "zone_or_path:string");
        setting.add("value", new JsonObject());
        register(registry, "set_setting",
                "Set a schema setting. Value is a boolean, count, choice id, or item-query list.",
                "settings", false, setting, ActionTools::setting, "scope", "key", "value");
        register(registry, "set_work", "Set a loaded resident's work permission and/or keenness (1 through 4).",
                "work", false, fields("resident_id:string", "vocation:string", "allowed:boolean", "keenness:integer"),
                ActionTools::work, "resident_id", "vocation");
        register(registry, "set_work_default",
                "Set work defaults for future residents of a kind; existing ones stay as is.",
                "work", false, fields("resident_kind:string", "vocation:string", "allowed:boolean", "keenness:integer"),
                ActionTools::workDefault, "resident_kind", "vocation");
        register(registry, "rename_resident", "Set a resident's custom display name; empty clears it.",
                "residents", false, fields("resident_id:string", "name:string"), ActionTools::rename,
                "resident_id", "name");
        JsonObject zone = fields("kind:string", "dimension:string", "parameters:object");
        zone.add("from", position(false));
        zone.add("to", position(false));
        register(registry, "create_zone", "Create a zone using its registered kind and inclusive corners.",
                "drawings", false, zone, ActionTools::zone, "kind", "dimension", "from", "to");
        JsonObject path = fields("kind:string", "dimension:string", "parameters:object");
        JsonObject points = new JsonObject();
        points.addProperty("type", "array");
        points.add("items", position(false));
        path.add("points", points);
        register(registry, "create_path", "Create a path from ordered points using a registered path kind.",
                "drawings", false, path, ActionTools::path, "kind", "dimension", "points");
        register(registry, "erase_zone_or_path",
                "Erase one zone or path by holding UUID. Always asks for confirmation.",
                "drawings", true, fields("id:string"), ActionTools::erase, "id");
        JsonObject build = DraftTools.fields(false);
        build.add("rotation", fields("rotation:integer").get("rotation"));
        build.add("mirror", fields("mirror:string").get("mirror"));
        ToolSpec commission = ToolRegistry.spec("commission_build",
                "Preview and commission survival building with optional settings. "
                        + "Provide exactly one of pattern (registered id) or source (full Starlark pattern). "
                        + "For points, at contains only dimension; otherwise at has XYZ and to is optional. "
                        + "rotation is 0/90/180/270. "
                        + "The native pattern path accepts mirror=none only. No instant creative placement.",
                build, "at");
        registry.register(new ActionRegistry.Spec(commission, "building", true),
                ActionTools::commission, ActionTools::commissionAsync);
        var cancel = ToolRegistry.spec("cancel_build", "Cancel a survival build order.", fields("id:string"), "id");
        registry.registerInternal(new ActionRegistry.Spec(cancel, "building", true), ActionTools::cancelBuild);
        JsonObject survey = fields("dimension:string");
        survey.add("points", points.deepCopy());
        register(registry, "request_survey", "Request a session survey corridor. Always requires manual confirmation. "
                        + "2..1025 points, total Euclidean length <=1024; half-width 16, vertical padding 32. "
                        + "Only loaded chunks may be read afterwards; keeps the newest 4 corridors per player.",
                "surveys", true, survey, Surveys::prepare, "dimension", "points");
    }

    private static void register(ActionRegistry registry, String id, String description, String category,
            boolean destructive, JsonObject fields,
            BiFunction<ToolContext, JsonObject, ActionRegistry.Plan> prepare,
            String... required) {
        ToolSpec spec = ToolRegistry.spec(id, description, fields, required);
        registry.register(new ActionRegistry.Spec(spec, category, destructive), prepare);
    }

    static JsonObject fields(String... definitions) {
        JsonObject fields = new JsonObject();
        for (String definition : definitions) {
            String[] parts = definition.split(":");
            JsonObject field = new JsonObject();
            field.addProperty("type", parts[1]);
            fields.add(parts[0], field);
        }
        return fields;
    }

    static JsonObject position(boolean dimension) {
        JsonObject properties = fields("x:integer", "y:integer", "z:integer");
        if (dimension) {
            properties.add("dimension", fields("dimension:string").get("dimension"));
        }
        return ToolRegistry.spec("position", "Position", properties,
                dimension ? new String[]{"dimension"} : new String[]{"x", "y", "z"}).schema();
    }

    private static ActionRegistry.Plan setting(ToolContext context, JsonObject args) {
        ResourceLocation scope = id(args, "scope");
        Optional<UUID> drawn = args.has("zone_or_path")
                ? Optional.of(UUID.fromString(args.get("zone_or_path").getAsString())) : Optional.empty();
        drawn.ifPresent(id -> {
            holdingLoaded(context, id);
            require(context.colony().claim(id).orElseThrow().owner().equals(scope), "wrong_setting_scope");
        });
        Schema.Setting declared = SettingChanges.schema(context.colony(), scope, drawn)
                .find(args.get("key").getAsString()).orElseThrow(() -> refused("unknown_setting"));
        ColonySettings.Value value = value(declared, args.get("value"));
        ColonySettings.Value valid = SettingChanges.validated(declared, value)
                .orElseThrow(() -> refused("invalid_setting_value"));
        JsonObject changes = args.deepCopy();
        changes.add("effective_value", ToolText.value(valid));
        return plan(context, "set_setting", changes, charter(context)
                + SettingChanges.settings(context.colony(), scope, drawn).flatMap(settings ->
                        settings.valueOf(declared.key())), () -> {
            boolean changed = SettingChanges.set(context.colony(), scope, drawn, declared.key(), valid);
            return changed(changes, changed);
        });
    }

    static ColonySettings.Value value(Schema.Setting declared, JsonElement value) {
        return switch (declared) {
            case Schema.Setting.Flag ignored -> {
                require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "invalid_setting_value");
                yield new ColonySettings.Value.Flag(value.getAsBoolean());
            }
            case Schema.Setting.Count ignored -> new ColonySettings.Value.Count(integer(value));
            case Schema.Setting.Choice ignored -> {
                require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "invalid_setting_value");
                ResourceLocation chosen = ResourceLocation.tryParse(value.getAsString());
                require(chosen != null, "invalid_setting_value");
                yield new ColonySettings.Value.Choice(chosen);
            }
            case Schema.Setting.Items ignored -> {
                require(value.isJsonArray() && value.getAsJsonArray().size() <= 64, "invalid_setting_value");
                List<ItemFilter> filters = new ArrayList<>();
                for (JsonElement entry : value.getAsJsonArray()) {
                    require(entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString(), "invalid_setting_value");
                    filters.add(ItemFilter.query(entry.getAsString()).orElseThrow(() -> refused("invalid_item_query")));
                }
                yield new ColonySettings.Value.Items(filters);
            }
        };
    }

    private static ActionRegistry.Plan work(ToolContext context, JsonObject args) {
        Body body = resident(context, args);
        var vocation = Vocations.of(id(args, "vocation")).orElseThrow(() -> refused("unknown_vocation"));
        var licences = body.licences();
        var trade = licences.trade(vocation).orElseThrow(() -> refused("unavailable_vocation"));
        require(args.has("allowed") || args.has("keenness"), "missing_work_change");
        boolean allowed = args.has("allowed") ? args.get("allowed").getAsBoolean() : licences.allows(trade);
        int priority = args.has("keenness") ? integer(args.get("keenness")) : licences.keennessOf(trade).number();
        Keenness keen = Keenness.ofNumber(priority).orElseThrow(() -> refused("invalid_keenness"));
        require(allowed || Participations.between(body.kind().id(), Stake.vocation(vocation.id()))
                != Participation.ALWAYS, "required_vocation");
        String state = charter(context) + licences.allows(trade) + "/" + licences.keennessOf(trade);
        boolean different = licences.allows(trade) != allowed || licences.keennessOf(trade) != keen;
        return plan(context, "set_work", args, state, () -> {
            if (!different) {
                return changed(args, false);
            }
            ResidentChanges.work(body, vocation, (held, known) -> {
                held.allow(known, allowed);
                held.setKeenness(known, keen);
            });
            return changed(args);
        });
    }

    private static ActionRegistry.Plan workDefault(ToolContext context, JsonObject args) {
        ResourceLocation kind = id(args, "resident_kind");
        ResourceLocation vocation = id(args, "vocation");
        Participation participation = Participations.between(kind, Stake.vocation(vocation));
        require(ResidentKinds.of(kind).filter(known -> known.workDefaults()).isPresent()
                && Vocations.of(vocation).isPresent() && participation != Participation.NONE, "unavailable_vocation");
        require(args.has("allowed") || args.has("keenness"), "missing_work_change");
        var rule = WorkDefaults.of(context.colony(), kind).of(vocation);
        boolean allowed = args.has("allowed") ? args.get("allowed").getAsBoolean() : rule.allowed();
        int priority = args.has("keenness") ? integer(args.get("keenness")) : rule.priority().number();
        require(Keenness.ofNumber(priority).isPresent(), "invalid_keenness");
        require(allowed || participation != Participation.ALWAYS, "required_vocation");
        return plan(context, "set_work_default", args, charter(context) + rule, () -> {
            if (rule.allowed() == allowed && rule.priority().number() == priority) {
                return changed(args, false);
            }
            require(WorkDefaults.set(context.colony(), kind, vocation, allowed, priority), "unavailable_vocation");
            return changed(args);
        });
    }

    private static ActionRegistry.Plan rename(ToolContext context, JsonObject args) {
        Body body = resident(context, args);
        String name = args.get("name").getAsString();
        require(name.length() <= 128, "name_too_long");
        String previous = body.mob().getCustomName() == null ? "" : Component.Serializer.toJson(
                body.mob().getCustomName(), context.player().registryAccess());
        JsonObject changes = args.deepCopy();
        changes.addProperty("name", name.trim());
        // Bind the preview to the charter and the name being replaced. Holdings and work
        // state can change during ordinary colony activity while the player reviews the name.
        String revisionState = context.colony().kept(Charters.OWNER) + "/" + previous;
        return plan(context, "rename_resident", changes, revisionState, () -> {
            Component wanted = name.trim().isEmpty() ? null : Component.literal(name.trim());
            boolean changed = !Objects.equals(body.mob().getCustomName(), wanted);
            if (changed) {
                ResidentChanges.rename(body, name);
            }
            return changed(changes, changed);
        });
    }

    private static ColonySettings drawingSettings(ResourceLocation kind, JsonObject args) {
        var declared = Enrollments.delegation(kind).orElseThrow(() -> refused("unknown_setting"));
        ColonySettings result = ColonySettings.empty();
        if (args.has("parameters")) {
            for (var entry : args.getAsJsonObject("parameters").entrySet()) {
                var setting = declared.schema().find(entry.getKey()).orElseThrow(() -> refused("unknown_setting"));
                var valid = SettingChanges.validated(setting, value(setting, entry.getValue()))
                        .orElseThrow(() -> refused("invalid_setting_value"));
                result = result.with(entry.getKey(), valid);
            }
        }
        return result;
    }

    private static ActionRegistry.Plan zone(ToolContext context, JsonObject args) {
        ServerLevel level = level(context, args.get("dimension").getAsString());
        ResourceLocation kind = id(args, "kind");
        var volume = ZoneDrafts.volumeOf(kind).orElseThrow(() -> refused("unknown_zone_kind"));
        BlockPos from = pos(args.getAsJsonObject("from"));
        BlockPos to = pos(args.getAsJsonObject("to"));
        region(level, from, to, volume.maxCells());
        JsonObject preview = args.deepCopy();
        var settings = drawingSettings(kind, args);
        var affected = ZoneDrafts.affected(level, kind, from, to, settings);
        Component summary = ActionSummaries.withParameters(ActionSummaries.of(context, "create_zone", args), args);
        if (!affected.isEmpty()) {
            JsonArray facilities = new JsonArray();
            affected.forEach(source -> {
                JsonObject facility = new JsonObject();
                facility.addProperty("id", source.id().toString());
                facility.addProperty("anchor", source.anchor().toString());
                facility.addProperty("block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                    level.getBlockState(source.anchor().block(level)).getBlock()).toString());
                facilities.add(facility);
            });
            preview.add("affected_facilities", facilities);
            summary = summary.copy().append("\n").append(ZoneDrafts.describeAffected(level, affected));
        }
        return new ActionRegistry.Plan(summary, preview, charter(context), () -> completed(createZone(
            context, args, level, kind, from, to, settings)));
    }

    private static ToolReply createZone(ToolContext context, JsonObject args, ServerLevel level, ResourceLocation kind,
            BlockPos from, BlockPos to, ColonySettings settings) {
        var zone = ZoneDrafts.zone(level, context.colony(), kind, from, to, settings)
            .orElseThrow(() -> refused("zone_conflict"));
        JsonObject result = args.deepCopy();
        result.addProperty("holding_id", zone.id().toString());
        return changed(result);
    }

    private static ActionRegistry.Plan path(ToolContext context, JsonObject args) {
        ServerLevel level = level(context, args.get("dimension").getAsString());
        ResourceLocation kind = id(args, "kind");
        var shape = Enrollments.delegation(kind).map(declared -> declared.shape())
                .filter(Shape.Path.class::isInstance).map(Shape.Path.class::cast)
                .orElseThrow(() -> refused("unknown_path_kind"));
        var array = args.getAsJsonArray("points");
        require(array.size() >= 2 && array.size() <= shape.maxPoints(), "invalid_path_size");
        List<BlockPos> points = array.asList().stream().map(value -> pos(value.getAsJsonObject())).toList();
        points.forEach(point -> loaded(level, point));
        var settings = drawingSettings(kind, args);
        return plan(context, "create_path", args, charter(context), () -> {
            var path = ZoneDrafts.path(level, context.colony(), kind, points, settings)
                    .orElseThrow(() -> refused("path_conflict"));
            JsonObject result = args.deepCopy();
            result.addProperty("holding_id", path.id().toString());
            return changed(result);
        });
    }

    private static ActionRegistry.Plan erase(ToolContext context, JsonObject args) {
        UUID id = UUID.fromString(args.get("id").getAsString());
        holdingLoaded(context, id);
        var held = context.colony().claim(id).orElseThrow(() -> refused("unknown_holding"));
        require(held.what() instanceof Claimed.Area || held.what() instanceof Claimed.Line, "not_zone_or_path");
        return plan(context, "erase_zone_or_path", args, charter(context) + held
                + ColonyFront.of(context.colony()).drawn(id).map(ColonySettings::save), () -> {
            require(ColonyFront.of(context.colony()).erase(id), "unknown_holding");
            return changed(args);
        });
    }

    private static ActionRegistry.Plan cancelBuild(ToolContext context, JsonObject args) {
        UUID id = UUID.fromString(args.get("id").getAsString());
        var source = Commissioning.source().orElseThrow(() -> refused("building_unavailable"));
        var order = source.orders(context.colony()).stream()
                .filter(found -> found.id().equals(id) && !found.cancelling())
                .findFirst().orElseThrow(() -> refused("unknown_build"));
        return new ActionRegistry.Plan(Component.translatable("folkways.assistant.preview.cancel_build", order.name()),
                args, order.id() + ":" + order.name(), () -> completed(source.cancel(context.colony(), id)
                        ? changed(args) : ToolReply.error("unknown_build")));
    }

    private static CompletableFuture<ActionRegistry.Plan> commissionAsync(ToolContext context, JsonObject args,
            Supplier<ToolContext> current) {
        return DraftTools.targetAsync(context, args, false).thenCompose(target -> {
            if (!current.get().colony().id().equals(context.colony().id())) {
                throw new ActionRegistry.Refused("stale");
            }
            return target.source().draw(target.level(), context.colony(), target.request()).thenApply(drawing -> {
                var refreshedContext = current.get();
                if (!refreshedContext.colony().id().equals(context.colony().id())) {
                    throw new ActionRegistry.Refused("stale");
                }
                if (drawing instanceof Commissioning.Drawing.Refused refused) {
                    throw new ActionRegistry.Refused("commission_refused", refused.code(), refused.refusal());
                }
                var ready = (Commissioning.Drawing.Ready) drawing;
                var refreshed = args.has("source") ? commission(refreshedContext, args, target)
                        : commission(refreshedContext, args);
                return new ActionRegistry.Plan(
                        refreshed.summary().copy().append("\n").append(ActionSummaries.drawing(ready)),
                        refreshed.changes(), refreshed.state() + ready.fingerprint(), refreshed.apply(),
                        target.level() == context.player().serverLevel() ? ready.ghosts() : List.of(),
                        refreshed.raise());
            });
        }).exceptionally(failure -> {
            if (failure.getCause() instanceof Commissioning.PatternFailure invalid) {
                throw new ActionRegistry.Refused("commission_refused", invalid.code(), invalid.refusal());
            }
            throw new CompletionException(failure);
        });
    }

    private static ActionRegistry.Plan commission(ToolContext context, JsonObject args) {
        return commission(context, args, DraftTools.target(context, args, false));
    }

    private static ActionRegistry.Plan commission(ToolContext context, JsonObject args, DraftTools.Target target) {
        var level = target.level();
        var source = target.source();
        var request = target.request();
        Component summary = ActionSummaries.of(context, "commission_build", args);
        StringBuilder world = new StringBuilder();
        if (request.points().isEmpty()) {
            for (BlockPos cell : BlockPos.betweenClosed(request.from(), request.to())) {
                var chunk = level.getChunkSource().getChunkNow(cell.getX() >> 4, cell.getZ() >> 4);
                require(chunk != null, "unloaded");
                world.append(cell).append(chunk.getBlockState(cell));
            }
        }
        JsonObject changes = args.deepCopy();
        changes.remove("source");
        return new ActionRegistry.Plan(summary, changes, charter(context) + source.fingerprint(request) + world,
                () -> source.commission(context.player(), level, context.colony(), request)
                        .thenApply(result -> filed(changes, result, false)),
                List.of(),
                () -> source.raise(context.player(), level, context.colony(), request)
                        .thenApply(result -> filed(changes, result, context.player().isCreative())));
    }

    private static ToolReply filed(JsonObject changes, Commissioning.Result result, boolean raised) {
        JsonObject data = changes.deepCopy();
        data.addProperty("changed", result.filed());
        if (raised) {
            data.addProperty("raised", result.filed());
        }
        if (!result.filed()) {
            data.addProperty("error", "commission_refused");
            data.addProperty("code", result.code().orElse("commission_refused"));
        }
        JsonObject text = new JsonObject();
        text.addProperty("$text", 0);
        data.add("message", text);
        return new ToolReply(data, List.of(result.message()));
    }

    private static Body resident(ToolContext context, JsonObject args) {
        UUID id = UUID.fromString(args.get("resident_id").getAsString());
        require(context.colony().residents().stream().anyMatch(resident -> resident.id().equals(id)),
                "not_colony_resident");
        return Residents.of(context.colony(), context.player().getServer(), id).orElseThrow(() -> refused("unloaded"));
    }

    private static void holdingLoaded(ToolContext context, UUID id) {
        var holding = context.colony().claim(id).orElseThrow(() -> refused("unknown_holding"));
        for (WorldPos cell : holding.what().cells()) {
            var resolved = WorldSpaces.resolve(context.player().getServer(), cell)
                    .orElseThrow(() -> refused("unloaded"));
            loaded(resolved.level(), resolved.cell());
        }
    }

    private static String charter(ToolContext context) {
        return context.colony().kept(Charters.OWNER).toString();
    }

    private static ActionRegistry.Plan plan(ToolContext context, String tool, JsonObject args, String state,
            Supplier<ToolReply> apply) {
        return new ActionRegistry.Plan(ActionSummaries.withParameters(ActionSummaries.of(context, tool, args), args),
                args.deepCopy(), state,
                () -> completed(apply.get()));
    }

    private static ToolReply changed(JsonObject details) {
        return changed(details, true);
    }

    private static ToolReply changed(JsonObject details, boolean changed) {
        JsonObject data = details.deepCopy();
        data.addProperty("changed", changed);
        return new ToolReply(data, List.of());
    }

    private static JsonObject error(String code) {
        JsonObject result = new JsonObject();
        result.addProperty("error", code);
        return result;
    }

    private static CompletableFuture<ToolReply> completed(ToolReply reply) {
        return CompletableFuture.completedFuture(reply);
    }

    private static ResourceLocation id(JsonObject args, String key) {
        return ResourceLocation.parse(args.get(key).getAsString());
    }

    private static ServerLevel level(ToolContext context, String id) {
        ServerLevel level = context.player().getServer().getLevel(ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.parse(id)));
        require(level != null, "unloaded");
        return level;
    }

    static int integer(JsonElement value) {
        try {
            require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "invalid_integer");
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException failure) {
            throw refused("invalid_integer");
        }
    }

    static BlockPos pos(JsonObject at) {
        require(at != null && at.has("x") && at.has("y") && at.has("z"), "invalid_arguments");
        return new BlockPos(integer(at.get("x")), integer(at.get("y")), integer(at.get("z")));
    }

    static void loaded(ServerLevel level, BlockPos pos) {
        require(level.isInWorldBounds(pos), "outside_world");
        require(level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null, "unloaded");
    }

    static void region(ServerLevel level, BlockPos from, BlockPos to, int max) {
        long x = Math.abs((long) from.getX() - to.getX()) + 1;
        long y = Math.abs((long) from.getY() - to.getY()) + 1;
        long z = Math.abs((long) from.getZ() - to.getZ()) + 1;
        require(x <= max && y <= max && z <= max && x * y <= max && x * y * z <= max, "region_too_large");
        for (BlockPos cell : BlockPos.betweenClosed(from, to)) {
            loaded(level, cell);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) {
            throw refused(code);
        }
    }

    private static ActionRegistry.Refused refused(String code) {
        return new ActionRegistry.Refused(code);
    }
}
