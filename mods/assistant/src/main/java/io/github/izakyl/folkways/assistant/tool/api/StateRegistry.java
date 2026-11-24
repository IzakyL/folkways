package io.github.izakyl.folkways.assistant.tool.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;

public final class StateRegistry {

    public static final StateRegistry STATES = new StateRegistry(ActionRegistry.ACTIONS);

    public record Operation(String name, JsonObject arguments) {
    }

    public record Change(String path, JsonElement before, JsonElement after) {
    }

    public static final class Snapshot {

        private final UUID colony;
        private final Map<String, JsonElement> values;
        private final Map<String, ToolReply> replies;
        private final Map<String, String> aliases;

        private Snapshot(UUID colony, Map<String, JsonElement> values, Map<String, ToolReply> replies,
                Map<String, String> aliases) {
            this.colony = colony;
            this.values = Map.copyOf(values);
            this.replies = Map.copyOf(replies);
            this.aliases = Map.copyOf(aliases);
        }

        public UUID colony() {
            return colony;
        }

        public long pathCount() {
            return (long) values.size() + replies.size();
        }

        public java.util.Set<String> paths() {
            var result = new java.util.HashSet<>(values.keySet());
            result.addAll(replies.keySet());
            return result;
        }

        public String canonicalPath(String path) {
            if (!path.startsWith("facilities/")) {
                return path;
            }
            int end = path.indexOf('/', "facilities/".length());
            String root = end < 0 ? path : path.substring(0, end);
            return aliases.getOrDefault(root, root) + (end < 0 ? "" : path.substring(end));
        }

        public ToolReply reply(String path) {
            return replies.get(path);
        }

        public JsonElement get(String path) {
            return values.getOrDefault(path, JsonNull.INSTANCE).deepCopy();
        }
    }

    @FunctionalInterface
    public interface Capture {

        void capture(ToolContext context, Map<String, JsonElement> values, Map<String, String> aliases);
    }

    @FunctionalInterface
    public interface Write {

        Operation prepare(List<String> keys, JsonElement value);
    }

    private record Node(List<String> pattern, Consumer<List<String>> validatePath, Write write, Write delete,
            BinaryOperator<JsonElement> merge, boolean creation) {
    }

    public record Field(String path, List<String> keys, boolean deletable,
            BiPredicate<JsonObject, JsonElement> valid) {
    }

    private record FieldPattern(List<String> pattern, int parentSegments, List<String> prefix, boolean deletable,
            BiPredicate<JsonObject, JsonElement> valid) {
    }

    private final List<FieldPattern> fields = new ArrayList<>();

    private final List<Node> nodes = new ArrayList<>();

    private final List<Capture> captures = new ArrayList<>();

    private final List<java.util.function.BiConsumer<ToolContext, Map<String, ToolReply>>> readers = new ArrayList<>();

    private final ActionRegistry operations;

    public void captureReplies(java.util.function.BiConsumer<ToolContext, Map<String, ToolReply>> reader) {
        readers.add(reader);
    }

    public StateRegistry(ActionRegistry operations) {
        this.operations = operations;
    }

    /** Register a server-thread reader that adds detached, plain values for its paths. */
    public void captureWith(Capture capture) {
        captures.add(capture);
    }

    /** Register a path pattern. A whole-segment * captures one escaped key. */
    public void register(String pattern, Consumer<List<String>> validatePath, Write write, Write delete) {
        register(pattern, validatePath, write, delete, (before, after) -> after);
    }

    public void register(String pattern, Consumer<List<String>> validatePath, Write write, Write delete,
            BinaryOperator<JsonElement> merge) {
        register(pattern, validatePath, write, delete, merge, false);
    }

    public void registerCreation(String pattern, Consumer<List<String>> validatePath, Write write, Write delete) {
        register(pattern, validatePath, write, delete, (before, after) -> after, true);
    }

    private void register(String pattern, Consumer<List<String>> validatePath, Write write, Write delete,
            BinaryOperator<JsonElement> merge, boolean creation) {
        List<String> parts = List.of(pattern.split("/"));
        if (nodes.stream().anyMatch(node -> node.pattern().equals(parts))) {
            throw new IllegalArgumentException("Duplicate state path " + pattern);
        }
        nodes.add(new Node(parts, validatePath, write, delete, merge, creation));
    }

    /** Register a nested dictionary field on a state node. */
    public void registerField(String pattern, int parentSegments, List<String> prefix, boolean deletable,
            BiPredicate<JsonObject, JsonElement> valid) {
        fields.add(new FieldPattern(List.of(pattern.split("/")), parentSegments, List.copyOf(prefix), deletable,
                valid));
    }

    public Field field(String path) {
        String[] parts = path.split("/", -1);
        for (FieldPattern field : fields) {
            if (parts.length != field.pattern().size()) {
                continue;
            }
            boolean matches = true;
            List<String> keys = new ArrayList<>(field.prefix());
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                String expected = field.pattern().get(index);
                if (!(expected.equals("*") ? !part.isEmpty() && segment(decode(part)).equals(part)
                        : expected.equals(part))) {
                    matches = false;
                    break;
                }
                if (index >= field.parentSegments()) {
                    keys.add(decode(part));
                }
            }
            if (matches) {
                if (path.length() > 512) {
                    throw new ActionRegistry.Refused("invalid_arguments");
                }
                String parent = String.join("/", Arrays.copyOf(parts, field.parentSegments() - 1)) + "/definition";
                checkRead(parent);
                return new Field(parent, List.copyOf(keys), field.deletable(), field.valid());
            }
        }
        return null;
    }

    public ActionRegistry operations() {
        return operations;
    }

    public Snapshot snapshot(ServerPlayer player) {
        return snapshot(player, true);
    }

    public Snapshot snapshot(ServerPlayer player, boolean includeReadOnly) {
        if (!player.getServer().isSameThread()) {
            throw new IllegalStateException("State snapshots must be captured on the server thread");
        }
        var colony = CurrentColony.of(player).orElse(null);
        if (colony == null) {
            return new Snapshot(null, Map.of(), Map.of(), Map.of());
        }
        ToolContext context = new ToolContext(player, colony);
        Map<String, JsonElement> values = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        captures.forEach(capture -> capture.capture(context, values, aliases));
        Map<String, ToolReply> replies = new LinkedHashMap<>();
        if (includeReadOnly) {
            readers.forEach(reader -> reader.accept(context, replies));
        }
        return new Snapshot(colony.id(), values, replies, aliases);
    }

    public void checkRead(String path) {
        resolve(path, node -> null);
    }

    public JsonElement stage(String path, JsonElement before, JsonElement after) {
        return stage(path, before, before, after);
    }

    public JsonElement stage(String path, JsonElement original, JsonElement before, JsonElement after) {
        return resolve(path, match -> {
            if (match.node().creation() && !after.isJsonNull() && !original.isJsonNull()) {
                throw new ActionRegistry.Refused("state_already_exists");
            }
            operation(match, after);
            return after.isJsonNull() ? after : match.node().merge().apply(before, after);
        });
    }

    public Operation operation(String path, JsonElement value) {
        return resolve(path, match -> operation(match, value));
    }

    private Operation operation(Match match, JsonElement value) {
        Write handler = value.isJsonNull() ? match.node().delete() : match.node().write();
        if (handler == null) {
            throw new ActionRegistry.Refused(value.isJsonNull() ? "state_delete_unsupported" : "state_read_only");
        }
        Operation operation = handler.prepare(match.keys(), value);
        operations.arguments(operation.name(), operation.arguments());
        return operation;
    }

    private record Match(Node node, List<String> keys) {
    }

    private <T> T resolve(String path, Function<Match, T> use) {
        if (path.length() > 512) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
        String[] parts = path.split("/", -1);
        for (Node node : nodes) {
            if (node.pattern().size() != parts.length) {
                continue;
            }
            List<String> keys = new ArrayList<>();
            boolean matches = true;
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                String expected = node.pattern().get(index);
                if (expected.equals("*") && !part.isEmpty() && segment(decode(part)).equals(part)) {
                    keys.add(decode(part));
                } else if (!expected.equals(part) || expected.equals("*")) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                node.validatePath().accept(keys);
                return use.apply(new Match(node, keys));
            }
        }
        throw new ActionRegistry.Refused("unknown_state_path");
    }

    public static String segment(String value) {
        return value.replace("%", "%25").replace("/", "%2F");
    }

    public static String decode(String value) {
        return value.replace("%2F", "/").replace("%25", "%");
    }
}
