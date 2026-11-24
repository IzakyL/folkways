package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.jpi.ai.SchemaValidator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;

public final class ToolRegistry {

    public static final ToolRegistry READS = new ToolRegistry();

    // These tools implement their own access rules and response format.
    public static final class DirectReads {

        private record Entry(ToolSpec spec, BiFunction<ServerPlayer, JsonObject, CompletableFuture<ToolReply>> read) {
        }

        private final Map<String, Entry> entries = new LinkedHashMap<>();

        public void register(ToolSpec spec, BiFunction<ServerPlayer, JsonObject, CompletableFuture<ToolReply>> read) {
            if (entries.putIfAbsent(spec.id(), new Entry(spec, read)) != null) {
                throw new IllegalArgumentException("Duplicate tool " + spec.id());
            }
        }

        public List<ToolSpec> specs() {
            return entries.values().stream().map(Entry::spec).toList();
        }

        public CompletableFuture<ToolReply> execute(ServerPlayer player, String name, JsonObject args) {
            requireServerThread(player);
            Entry entry = entries.get(name);
            if (entry == null) {
                return CompletableFuture.completedFuture(ToolReply.error("unknown_tool"));
            }
            var errors = SchemaValidator.validate(entry.spec().schema(), args);
            if (!errors.isEmpty()) {
                return CompletableFuture.completedFuture(ToolReply.invalid(describeSchemaErrors(errors)));
            }
            return entry.read().apply(player, args);
        }
    }

    public static final DirectReads DIRECT_READS = new DirectReads();

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private final Map<ServerPlayer, Window> windows = new WeakHashMap<>();

    private record Entry(ToolSpec spec, BiFunction<ToolContext, JsonObject, ToolReply> read,
            Predicate<ServerPlayer> withoutColony) {
    }

    private record Window(long from, int count) {
    }

    public void register(ToolSpec spec, BiFunction<ToolContext, JsonObject, ToolReply> read) {
        register(spec, read, player -> false);
    }

    public void register(ToolSpec spec, BiFunction<ToolContext, JsonObject, ToolReply> read,
            Predicate<ServerPlayer> withoutColony) {
        if (entries.putIfAbsent(spec.id(), new Entry(spec, read, withoutColony)) != null) {
            throw new IllegalArgumentException("Duplicate tool " + spec.id());
        }
    }

    public List<ToolSpec> specs() {
        return entries.values().stream().map(Entry::spec).toList();
    }

    public ToolReply execute(ServerPlayer player, String name, JsonObject args) {
        requireServerThread(player);
        Entry entry = entries.get(name);
        if (entry == null) {
            return ToolReply.error("unknown_tool");
        }
        Colony colony = CurrentColony.of(player).orElse(null);
        if (colony == null && !entry.withoutColony().test(player)) {
            return ToolReply.error("not_member");
        }
        try {
            var errors = SchemaValidator.validate(entry.spec().schema(), args);
            if (!errors.isEmpty()) {
                return ToolReply.invalid(describeSchemaErrors(errors));
            }
            ToolReply reply = entry.read().apply(new ToolContext(player, colony), args);
            if (colony != null) {
                reply.data().addProperty("colony_id", colony.id().toString());
            }
            if (!reply.data().has("capture_tick")) {
                reply.data().addProperty("capture_tick", player.serverLevel().getGameTime());
            }
            return reply.capped(player.registryAccess());
        } catch (IllegalArgumentException exception) {
            return ToolReply.invalid(exception.getMessage());
        }
    }

    private static void requireServerThread(ServerPlayer player) {
        if (!player.getServer().isSameThread()) {
            throw new IllegalStateException("Tools must run on the server thread");
        }
    }

    public boolean admit(ServerPlayer player) {
        long now = System.nanoTime();
        Window window = windows.get(player);
        if (window == null || now - window.from() >= 10_000_000_000L) {
            window = new Window(now, 0);
        }
        if (window.count() >= 24) {
            return false;
        }
        windows.put(player, new Window(window.from(), window.count() + 1));
        return true;
    }

    public static String describeSchemaErrors(List<String> errors) {
        return String.join("; ", errors.stream().map(error -> error.startsWith("$.") ? error.substring(2) : error)
                .toList());
    }

    public static ToolSpec spec(String id, String description, JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.addProperty("additionalProperties", false);
        JsonArray keys = new JsonArray();
        for (String key : required) {
            keys.add(key);
        }
        schema.add("required", keys);
        return new ToolSpec(id, description, schema);
    }
}
