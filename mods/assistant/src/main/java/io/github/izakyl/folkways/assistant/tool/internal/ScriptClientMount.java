package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptSteps;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.util.List;
import java.util.TreeSet;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

final class ScriptClientMount {

    @FunctionalInterface
    interface History {
        JsonElement read(String path) throws EvalException, InterruptedException;
    }

    private static final List<String> MOUNTS = List.of("history", "marks", "scratch", "view");

    private final JsonObject view;
    private final JsonElement marks;
    private final History history;
    private JsonObject scratch;
    private volatile String updated;
    private volatile int historyReads;

    ScriptClientMount(JsonObject view, String scratch, History history) {
        this.view = view.deepCopy();
        JsonElement capturedMarks = this.view.remove("marks");
        this.marks = capturedMarks == null ? JsonNull.INSTANCE : capturedMarks;
        this.scratch = JsonParser.parseString(scratch).getAsJsonObject();
        this.history = history;
    }

    void validate() throws EvalException {
        try (Mutability mutability = Mutability.create("assistant scratch")) {
            ScriptValues.readScratch(scratch, mutability);
        }
    }

    String updated() {
        return updated;
    }

    int historyReads() {
        return historyReads;
    }

    Object access(String method, String path, Tuple args, StarlarkThread thread)
            throws EvalException, InterruptedException {
        String[] parts = path.split("/", -1);
        String mount = parts.length > 1 ? parts[1] : "";
        String key = parts.length == 3 ? parts[2] : null;
        if (method.equals("ls")) {
            return ScriptValues.readPlain(list(path, thread), thread);
        }
        if (parts.length > 3 || (key != null && key.isEmpty())) {
            throw new ActionRegistry.Refused("unknown_state_path");
        }
        if (method.equals("get")) {
            if (mount.equals("scratch")) {
                JsonElement value = key == null ? scratch : member(scratch, decode(key));
                return ScriptValues.readScratch(value, thread);
            }
            JsonElement value = switch (mount) {
                case "view", "marks" -> {
                    if (key != null) {
                        throw new ActionRegistry.Refused("unknown_state_path");
                    }
                    yield mount.equals("view") ? view : marks;
                }
                case "history" -> history(path);
                default -> throw new ActionRegistry.Refused("unknown_state_path");
            };
            return ScriptValues.readPlain(value, thread);
        }
        if (!mount.equals("scratch")) {
            throw new ActionRegistry.Refused("state_read_only");
        }
        write(method, key, args);
        return Starlark.NONE;
    }

    private void write(String method, String key, Tuple args) throws EvalException {
        JsonObject next = scratch.deepCopy();
        try {
            if (method.equals("delete")) {
                if (key == null) {
                    next = new JsonObject();
                } else {
                    next.remove(decode(key));
                }
            } else {
                JsonElement value = JsonParser.parseString(ScriptValues.writeScratch(args.get(1)));
                if (key == null) {
                    if (!value.isJsonObject()) {
                        throw new ActionRegistry.Refused("invalid_arguments");
                    }
                    next = value.getAsJsonObject();
                } else {
                    next.add(decode(key), value);
                }
            }
            String encoded = next.toString();
            if (!ScriptValues.fitsScratch(encoded)) {
                throw new ActionRegistry.Refused("scratch_size_cap");
            }
            try (Mutability mutability = Mutability.create("scratch write")) {
                ScriptValues.readScratch(next, mutability);
            }
            scratch = next;
            updated = encoded;
        } catch (ScriptFault limit) {
            if (limit.kind() != ScriptFault.Kind.RESULT_SIZE) {
                throw limit;
            }
            throw new ActionRegistry.Refused("scratch_size_cap");
        }
    }

    private JsonElement history(String path) throws EvalException, InterruptedException {
        JsonElement data = history.read(path);
        historyReads++;
        return data;
    }

    private JsonElement list(String path, StarlarkThread thread) throws EvalException, InterruptedException {
        return switch (path) {
            case "client" -> strings(MOUNTS);
            case "client/scratch" -> {
                var keys = new TreeSet<String>();
                for (String key : scratch.keySet()) {
                    ScriptSteps.charge(thread, 1L + key.length());
                    if (!key.isEmpty()) {
                        keys.add(StateRegistry.segment(key));
                    }
                }
                yield strings(keys);
            }
            case "client/history" -> history(path);
            default -> new JsonArray();
        };
    }

    void glob(String[] pattern, TreeSet<String> result, StarlarkThread thread)
            throws EvalException, InterruptedException {
        if (pattern.length == 0 || !(pattern[0].equals("client") || pattern[0].equals("*"))) {
            return;
        }
        ScriptState.addMatches("client", pattern, true, result);
        for (String mount : MOUNTS) {
            String path = "client/" + mount;
            ScriptState.addMatches(path, pattern, true, result);
            if (pattern.length == 3 && (pattern[1].equals(mount) || pattern[1].equals("*"))) {
                for (JsonElement child : list(path, thread).getAsJsonArray()) {
                    String segment = child.getAsString();
                    ScriptSteps.charge(thread, 1L + segment.length());
                    ScriptState.addMatches(path + "/" + segment, pattern, true, result);
                }
            }
        }
    }

    private static JsonArray strings(Iterable<String> values) {
        JsonArray result = new JsonArray();
        values.forEach(result::add);
        return result;
    }

    private static JsonElement member(JsonObject object, String key) {
        return object.has(key) ? object.get(key) : JsonNull.INSTANCE;
    }

    private static String decode(String segment) {
        String key = segment.replace("%2F", "/").replace("%25", "%");
        if (!StateRegistry.segment(key).equals(segment)) {
            throw new ActionRegistry.Refused("unknown_state_path");
        }
        return key;
    }
}
