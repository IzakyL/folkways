package io.github.izakyl.folkways.assistant.tool.internal;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.StateRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptSteps;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.ResourceLocationException;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkCallable;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

final class ScriptState {

    @FunctionalInterface
    interface Budget {

        void charge() throws EvalException;
    }

    private final StateRegistry registry;
    private final StateRegistry.Snapshot snapshot;
    private final Budget budget;
    private final ScriptClientMount client;
    private final String[] code;
    private final Map<String, JsonElement> writes = new LinkedHashMap<>();
    private final ScriptValues input = ScriptValues.output(ToolReply.MAX_BYTES);
    private int count;

    ScriptState(StateRegistry registry, StateRegistry.Snapshot snapshot, Budget budget, String[] code,
            ScriptClientMount client) {
        this.registry = registry;
        this.snapshot = snapshot;
        this.budget = budget;
        this.code = code;
        this.client = client;
    }

    void bind(Map<String, Object> bindings) {
        for (String name : List.of("ls", "get", "set", "delete", "glob")) {
            bindings.put(name, primitive(name));
        }
    }

    private StarlarkCallable primitive(String field) {
        return new StarlarkCallable() {

            @Override
            public String getName() {
                return field;
            }

            @Override
            public Object call(StarlarkThread thread, Tuple args, Dict<String, Object> kwargs)
                    throws EvalException, InterruptedException {
                budget.charge();
                int arity = field.equals("set") ? 2 : 1;
                if (args.size() != arity || !kwargs.isEmpty() || !(args.get(0) instanceof String path)) {
                    throw Starlark.errorf("%s takes a string path%s", getName(), arity == 2 ? " and a value" : "");
                }
                ScriptSteps.charge(thread, 1L + path.length());
                try {
                    if (!field.equals("glob") && (path.equals("client") || path.startsWith("client/"))) {
                        return client.access(field, path, args, thread);
                    }
                    boolean glob = field.equals("glob");
                    if (snapshot.colony() == null) {
                        if (glob && (path.equals("client") || path.startsWith("client/"))) {
                            var matches = new TreeSet<String>();
                            client.glob(path.split("/", -1), matches, thread);
                            return StarlarkList.copyOf(thread.mutability(), matches);
                        }
                        throw new ActionRegistry.Refused("not_member");
                    }
                    path = snapshot.canonicalPath(path);
                    if (field.equals("ls") || glob) {
                        return StarlarkList.copyOf(thread.mutability(), list(path, glob, thread));
                    }
                    String root = facilityRoot(path);
                    if (!field.equals("get") && root != null && !root.equals(path)
                            && JsonNull.INSTANCE.equals(writes.get(root))) {
                        throw new ActionRegistry.Refused("unknown_facility");
                    }
                    var nested = registry.field(path);
                    if (nested != null && (!path.contains("/parameters/")
                            || valueAt(nested.path()).isJsonObject())) {
                        return nested(field, nested, args, thread);
                    }
                    if (field.equals("get")) {
                        registry.checkRead(path);
                        ToolReply reply = facilityRemoved(path) ? null : snapshot.reply(path);
                        if (reply != null) {
                            JsonObject data = reply.data();
                            if (path.startsWith("residents/") && writes.containsKey(path + "/name")) {
                                data = data.deepCopy();
                                data.add("name", writes.get(path + "/name"));
                            }
                            return ScriptValues.readOutput(data, reply.texts(), thread);
                        }
                        return ScriptValues.readPlain(valueAt(path), thread);
                    }
                    chargeIntent();
                    JsonElement value = field.equals("set") ? plain(args.get(1), path.startsWith("builds/")
                            ? ScriptValues.output(6 * Profile.MAX_PATTERN_SOURCE + ToolReply.MAX_BYTES) : input)
                            : JsonNull.INSTANCE;
                    JsonElement staged = registry.stage(path, snapshot.get(path), valueAt(path), value);
                    if (value.isJsonNull() && path.equals(root)) {
                        String prefix = root + "/";
                        writes.keySet().removeIf(key -> key.startsWith(prefix));
                    }
                    if (value.isJsonNull() && snapshot.get(path).isJsonNull() && writes.containsKey(path)) {
                        writes.remove(path);
                    } else {
                        writes.put(path, staged);
                    }
                    return Starlark.NONE;
                } catch (ActionRegistry.Refused refusal) {
                    code[0] = refusal.getMessage();
                    throw Starlark.errorf("%s(%s): %s", getName(), path, refusal.getMessage());
                } catch (IllegalArgumentException | ResourceLocationException invalid) {
                    code[0] = "invalid_arguments";
                    throw Starlark.errorf("%s: %s", getName(), invalid.getMessage());
                }
            }
        };
    }

    private Object nested(String method, StateRegistry.Field field, Tuple args, StarlarkThread thread)
            throws EvalException {
        JsonElement root = valueAt(field.path());
        if (!method.equals("get")) {
            root = root.deepCopy();
        }
        List<String> keys = field.keys();
        JsonElement parent = root;
        for (String key : keys.subList(0, keys.size() - 1)) {
            ScriptSteps.charge(thread, 1L + key.length());
            parent = child(parent, key);
        }
        String key = keys.getLast();
        if (method.equals("get")) {
            return ScriptValues.readPlain(child(parent, key), thread);
        }
        chargeIntent();
        if (!parent.isJsonObject()) {
            throw new ActionRegistry.Refused("unknown_state_path");
        }
        if (method.equals("delete")) {
            if (!field.deletable()) {
                throw new ActionRegistry.Refused("state_delete_unsupported");
            }
            parent.getAsJsonObject().remove(key);
        } else {
            JsonElement value = plain(args.get(1), input);
            if (!field.valid().test(parent.getAsJsonObject(), value)) {
                throw new ActionRegistry.Refused("invalid_arguments");
            }
            parent.getAsJsonObject().add(key, value);
        }
        writes.put(field.path(), registry.stage(field.path(), valueAt(field.path()), root));
        return Starlark.NONE;
    }

    private static JsonElement child(JsonElement parent, String key) {
        return parent.isJsonObject() && parent.getAsJsonObject().has(key)
                ? parent.getAsJsonObject().get(key) : JsonNull.INSTANCE;
    }

    private static JsonElement plain(Object value, ScriptValues encoder) throws EvalException {
        JsonElement result = encoder.write(value);
        if (result.isJsonNull() || encoder.hasText()) {
            throw new ActionRegistry.Refused("invalid_arguments");
        }
        return result;
    }

    private void chargeIntent() {
        if (++count > ScriptHost.MAX_INTENTS) {
            throw new ActionRegistry.Refused("too_many_intents");
        }
    }

    private List<String> list(String path, boolean glob, StarlarkThread thread)
            throws EvalException, InterruptedException {
        var paths = new TreeSet<String>();
        paths.addAll(List.of("builds", "client", "facilities", "residents", "routines", "scripts", "surveys", "work_defaults"));
        ScriptSteps.charge(thread, snapshot.pathCount() + writes.size());
        paths.addAll(snapshot.paths());
        paths.addAll(writes.keySet());
        var result = new TreeSet<String>();
        String[] pattern = path.isEmpty() ? new String[0] : path.split("/", -1);
        for (String entry : paths) {
            ScriptSteps.charge(thread, 1L + entry.length());
            if (writes.containsKey(entry) && writes.get(entry).isJsonNull()) {
                continue;
            }
            if (facilityRemoved(entry) || replacedParameters(entry)) {
                continue;
            }
            addMatches(entry, pattern, glob, result);
            if (entry.endsWith("/definition") && valueAt(entry) instanceof JsonObject definition) {
                String prefix = entry.substring(0, entry.lastIndexOf('/'));
                addMatches(prefix + "/runs", pattern, glob, result);
                for (String collection : List.of("parameters", "triggers", "routines")) {
                    addMatches(prefix + "/" + collection, pattern, glob, result);
                    if (definition.get(collection) instanceof JsonObject children) {
                        for (var child : children.entrySet()) {
                            ScriptSteps.charge(thread, 1L + child.getKey().length());
                            String childPath = prefix + "/" + collection + "/" + StateRegistry.segment(child.getKey());
                            addMatches(childPath, pattern, glob, result);
                            if (collection.equals("triggers") && child.getValue() instanceof JsonObject trigger) {
                                for (String field : List.of("enabled", "retries", "backoff")) {
                                    if (trigger.has(field)) {
                                        addMatches(childPath + "/" + field, pattern, glob, result);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (glob) {
            client.glob(pattern, result, thread);
        }
        return List.copyOf(result);
    }

    static void addMatches(String path, String[] pattern, boolean glob, TreeSet<String> result) {
        String[] segments = path.split("/");
        if (segments.length < pattern.length + (glob ? 0 : 1)) {
            return;
        }
        for (int i = 0; i < pattern.length; i++) {
            if (!pattern[i].equals(segments[i]) && !(glob && pattern[i].equals("*"))) {
                return;
            }
        }
        result.add(glob ? String.join("/", java.util.Arrays.copyOf(segments, pattern.length))
                : segments[pattern.length]);
    }

    private JsonElement valueAt(String path) {
        if (writes.containsKey(path)) {
            return writes.get(path);
        }
        if (facilityRemoved(path) || replacedParameters(path)) {
            return JsonNull.INSTANCE;
        }
        return snapshot.get(path);
    }

    private boolean facilityRemoved(String path) {
        String root = facilityRoot(path);
        return root != null && (JsonNull.INSTANCE.equals(writes.get(root))
                || JsonNull.INSTANCE.equals(writes.get(root + "/definition")));
    }

    private boolean replacedParameters(String path) {
        String root = facilityRoot(path);
        return root != null && path.startsWith(root + "/parameters/") && writes.containsKey(root + "/definition");
    }

    private static String facilityRoot(String path) {
        if (!path.startsWith("facilities/")) {
            return null;
        }
        int end = path.indexOf('/', "facilities/".length());
        return end < 0 ? path : path.substring(0, end);
    }

    List<StateRegistry.Change> changes() {
        return writes.entrySet().stream()
                .map(entry -> new StateRegistry.Change(entry.getKey(), snapshot.get(entry.getKey()), entry.getValue()))
                .filter(change -> !change.before().equals(change.after())).toList();
    }
}
