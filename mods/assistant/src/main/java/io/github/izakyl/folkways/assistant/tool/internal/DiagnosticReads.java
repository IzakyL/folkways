package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.colony.api.SupplySources;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.runtime.RecentErrors;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;

final class DiagnosticReads {

    private static final int SOURCE_LIMIT = 128;

    private DiagnosticReads() {
    }

    private static JsonObject field(String name) {
        JsonObject fields = new JsonObject();
        JsonObject type = new JsonObject();
        type.addProperty("type", "string");
        fields.add(name, type);
        return fields;
    }

    static void register(ToolRegistry registry) {
        registry.register(ToolRegistry.spec("reachable", "Read physical logistics sources for an exact item ID. "
                + "Returns sources and invisible bound sources with reasons, up to 128 total. Unloaded, unopened "
                + "loot, unsupported, unauthorized and workshop-managed stores are not counted. Network data "
                + "comes from the last published snapshot. Counts precede reservations and do not prove a path exists.",
                field("item"), "item"), DiagnosticReads::reachable);
        JsonObject limit = new JsonObject();
        JsonObject integer = new JsonObject();
        integer.addProperty("type", "integer");
        limit.add("limit", integer);
        registry.register(ToolRegistry.spec("recent_errors", "Read recent Folkways WARN/ERROR logs, "
                + "deduplicated by fingerprint within each colony (default limit 20, range 1..200). Includes "
                + "count, first_time, last_time, exception_message, cause/suppressed chains (4 levels, 4 frames each), "
                + "dropped and since. Messages are capped at 500 characters. Members see only their current colony; "
                + "OPs see all origins, including null colony_id. Budgeting sets truncated and retains newest rows.",
                limit),
                DiagnosticReads::errors, WorldReads::permitted);
    }

    private static ToolReply reachable(ToolContext context, JsonObject args) {
        ResourceLocation id = ResourceLocation.parse(args.get("item").getAsString());
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return ToolReply.error("unknown_item");
        }
        ItemSpec item = ItemSpec.of(id);
        ToolText text = new ToolText();
        JsonArray sources = new JsonArray();
        JsonArray invisible = new JsonArray();
        ReadScope scope = new ReadScope(context);
        Set<WorldPos> counted = new LinkedHashSet<>();
        boolean limited = false;
        var server = context.player().getServer();
        for (ServerLevel level : server.getAllLevels()) {
            for (WorldPos at : context.colony().storesIn(level.dimension())) {
                if (sources.size() + invisible.size() >= SOURCE_LIMIT) {
                    limited = true;
                    break;
                }
                JsonObject row = new JsonObject();
                row.addProperty("kind", "container");
                row.add("at", ToolText.position(at, server));
                var resolved = WorldSpaces.storage(level, at);
                if (resolved.isEmpty() || !level.hasChunkAt(resolved.get())) {
                    invisible(invisible, row, "unloaded", text);
                    continue;
                }
                BlockPos pos = resolved.get();
                if (!scope.contains(level, pos)) {
                    invisible(invisible, row, "unauthorized", text);
                    continue;
                }
                WorldPos canonical = WorldPos.of(level, Containers.anchor(level, pos));
                if (!counted.add(canonical)) {
                    continue;
                }
                if (StoreAccess.attached(level, pos)) {
                    invisible(invisible, row, "managed_by_facility", text);
                    continue;
                }
                if (StoreAccess.owningPart(level, pos).isPresent()) {
                    invisible(invisible, row, "managed_by_facility", text);
                    continue;
                }
                var state = level.getBlockState(pos);
                if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
                    if (!level.hasChunkAt(other)) {
                        invisible(invisible, row, "unloaded", text);
                        continue;
                    }
                    if (!scope.contains(level, other)) {
                        invisible(invisible, row, "unauthorized", text);
                        continue;
                    }
                }
                var raw = Containers.at(level, pos);
                if (raw.isEmpty()) {
                    invisible(invisible, row, Containers.blockEntity(level, pos).isPresent()
                            ? "unsupported_storage" : "not_container", text);
                } else if (!Containers.known(raw.get())) {
                    invisible(invisible, row, "unknown_contents", text);
                } else if (!StoreRule.takes(context.colony().storeRules(at))) {
                    invisible(invisible, row, "take_disabled", text);
                } else {
                    var container = context.colony().store(level, at);
                    if (container.isEmpty()) {
                        invisible(invisible, row, "no_slots", text);
                    } else {
                        long count = Goods.countIn(container.get(), item);
                        if (count > 0) {
                            row.addProperty("count", count);
                            sources.add(row);
                        }
                    }
                }
            }
        }
        for (var resident : context.colony().residents()) {
            if (sources.size() + invisible.size() >= SOURCE_LIMIT) {
                limited = true;
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("kind", "resident");
            row.addProperty("resident", resident.id().toString());
            var body = Residents.of(context.colony(), server, resident.id());
            if (body.isEmpty()) {
                invisible(invisible, row, "unloaded", text);
            } else {
                long count = Goods.countIn(body.get().pack(), item);
                if (count > 0) {
                    row.add("at", ToolText.position(WorldSpaces.at(body.get().mob()), server));
                    row.addProperty("count", count);
                    sources.add(row);
                }
            }
        }
        for (var source : SupplySources.of(context.colony(), item)) {
            if (sources.size() + invisible.size() >= SOURCE_LIMIT) {
                limited = true;
                break;
            }
            JsonObject row = new JsonObject();
            row.addProperty("kind", source.kind());
            row.addProperty("id", source.id().toString());
            source.at().ifPresent(at -> row.add("at", ToolText.position(at, server)));
            row.addProperty("capture_tick", source.captureTick());
            if (source.reason().isPresent()) {
                invisible(invisible, row, source.reason().get(), text);
            } else if (source.count() > 0) {
                row.addProperty("count", source.count());
                sources.add(row);
            }
        }
        JsonObject data = new JsonObject();
        data.addProperty("item", id.toString());
        data.add("sources", sources);
        data.add("invisible", invisible);
        data.addProperty("limited", limited);
        return text.reply(data);
    }

    private static void invisible(JsonArray rows, JsonObject row, String reason, ToolText text) {
        row.addProperty("reason", reason);
        row.add("text", text.text(Component.translatable("folkways.assistant.source." + reason)));
        rows.add(row);
    }

    private static ToolReply errors(ToolContext context, JsonObject args) {
        int limit;
        try {
            limit = args.has("limit") ? args.get("limit").getAsBigDecimal().intValueExact() : 20;
        } catch (ArithmeticException invalid) {
            return ToolReply.error("bounds_limit");
        }
        if (limit < 1 || limit > 200) {
            return ToolReply.error("bounds_limit");
        }
        Optional<UUID> colony = WorldReads.permitted(context.player())
                ? Optional.empty() : Optional.of(context.colony().id());
        var snapshot = RecentErrors.snapshot(limit, colony);
        JsonObject data = new JsonObject();
        data.add("errors", new JsonArray());
        data.addProperty("dropped", snapshot.dropped());
        data.addProperty("since", snapshot.since());
        data.addProperty("truncated", false);
        data.addProperty("capture_tick", context.player().serverLevel().getGameTime());
        if (context.colony() != null) {
            data.addProperty("colony_id", context.colony().id().toString());
        }
        // Leave room for the run result envelope and the transport's length prefixes.
        int remaining = ToolReply.MAX_BYTES - 1024 - bytes(data);
        List<JsonObject> rows = new ArrayList<>();
        boolean truncated = false;
        for (var entry : snapshot.entries().reversed()) {
            JsonObject row = error(entry);
            int size = bytes(row) + (rows.isEmpty() ? 0 : 1);
            if (size > remaining && rows.isEmpty()) {
                row.remove("cause");
                row.add("suppressed", new JsonArray());
                row.addProperty("details_truncated", true);
                size = bytes(row);
                truncated = true;
            }
            if (size > remaining) {
                truncated = true;
                break;
            }
            rows.add(row);
            remaining -= size;
        }
        JsonArray errors = new JsonArray();
        rows.reversed().forEach(errors::add);
        data.add("errors", errors);
        data.addProperty("truncated", truncated);
        return new ToolReply(data, List.of());
    }

    private static JsonObject error(RecentErrors.Entry entry) {
        JsonObject row = new JsonObject();
        if (entry.colony().isPresent()) {
            row.addProperty("colony_id", entry.colony().get().toString());
        } else {
            row.add("colony_id", JsonNull.INSTANCE);
        }
        row.addProperty("time", entry.time());
        row.addProperty("first_time", entry.firstTime());
        row.addProperty("last_time", entry.lastTime());
        row.addProperty("count", entry.count());
        row.addProperty("level", entry.level());
        row.addProperty("logger", entry.logger());
        row.addProperty("message", entry.message());
        entry.exception().ifPresent(exception -> row.addProperty("exception", exception));
        entry.exceptionMessage().ifPresent(message -> row.addProperty("exception_message", message));
        row.add("frames", strings(entry.frames()));
        entry.cause().ifPresent(cause -> row.add("cause", failure(cause)));
        row.add("suppressed", failures(entry.suppressed()));
        row.addProperty("details_truncated", entry.detailsTruncated());
        return row;
    }

    private static JsonObject failure(RecentErrors.Failure error) {
        JsonObject row = new JsonObject();
        row.addProperty("exception", error.exception());
        row.addProperty("message", error.message());
        row.add("frames", strings(error.frames()));
        error.cause().ifPresent(cause -> row.add("cause", failure(cause)));
        row.add("suppressed", failures(error.suppressed()));
        return row;
    }

    private static JsonArray failures(List<RecentErrors.Failure> errors) {
        JsonArray rows = new JsonArray();
        errors.forEach(error -> rows.add(failure(error)));
        return rows;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray rows = new JsonArray();
        values.forEach(rows::add);
        return rows;
    }

    private static int bytes(JsonObject data) {
        return data.toString().getBytes(StandardCharsets.UTF_8).length;
    }
}
