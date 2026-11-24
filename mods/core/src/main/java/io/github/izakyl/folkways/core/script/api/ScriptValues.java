package io.github.izakyl.folkways.core.script.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Mutability;
import net.starlark.java.eval.Printer;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkValue;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.Tuple;

public final class ScriptValues {

    @StarlarkBuiltin(name = "text", doc = "Opaque text localized by the receiving client; compare ids instead.")
    static final class Text implements StarlarkValue {

        private final Component words;

        Text(Component words) {
            this.words = words.copy();
        }

        @Override
        public boolean isImmutable() {
            return true;
        }

        @Override
        public void repr(Printer printer) {
            printer.append("<text>");
        }
    }

    private final List<Component> texts = new ArrayList<>();

    public static final int MAX_SCRATCH_BYTES = 256 * 1024;

    private static final int FACILITY_BYTES = 32_768;
    private static final int MAX_DEPTH = 64;

    private enum Mode {
        OUTPUT, SCRATCH, FACILITY
    }

    private final Mode mode;
    private final int limit;

    private int size;

    public static ScriptValues output(int maxCharacters) {
        return new ScriptValues(Mode.OUTPUT, maxCharacters);
    }

    public static Object readPlain(JsonElement value, Mutability mutability) throws EvalException {
        return read(value, List.of(), mutability, 0, Mode.OUTPUT, null);
    }

    public boolean hasText() {
        return !texts.isEmpty();
    }

    private ScriptValues(Mode mode, int limit) {
        this.mode = mode;
        this.limit = limit;
    }

    public static boolean fitsScratch(String data) {
        return data.length() <= MAX_SCRATCH_BYTES
                && data.getBytes(StandardCharsets.UTF_8).length <= MAX_SCRATCH_BYTES;
    }

    public static String writeScratch(Object value) throws EvalException {
        String data = new ScriptValues(Mode.SCRATCH, MAX_SCRATCH_BYTES).write(value).toString();
        if (!fitsScratch(data)) {
            throw new ScriptFault(ScriptFault.Kind.RESULT_SIZE, "Scratch exceeds its byte limit", null);
        }
        return data;
    }

    public static Object readScratch(JsonElement data, Mutability mutability) throws EvalException {
        return read(data, List.of(), mutability, 0, Mode.SCRATCH, null);
    }

    public static Object readOutput(JsonElement data, List<Component> texts, Mutability mutability)
            throws EvalException {
        return read(data, texts, mutability, 0, Mode.OUTPUT, null);
    }

    public static Object readPlain(JsonElement value, StarlarkThread thread) throws EvalException {
        return readOutput(value, List.of(), thread);
    }

    public static Object readOutput(JsonElement value, List<Component> texts, StarlarkThread thread)
            throws EvalException {
        return read(value, texts, thread.mutability(), 0, Mode.OUTPUT, thread);
    }

    public static Object readScratch(JsonElement value, StarlarkThread thread) throws EvalException {
        return read(value, List.of(), thread.mutability(), 0, Mode.SCRATCH, thread);
    }

    private static Object read(JsonElement value, List<Component> texts, Mutability mutability, int depth,
            Mode mode, StarlarkThread thread) throws EvalException {
        ScriptRuntime.checkTime();
        if (thread != null) {
            ScriptSteps.charge(thread, 1);
        }
        if (mode != Mode.OUTPUT && depth > MAX_DEPTH) {
            throw Starlark.errorf(mode == Mode.SCRATCH ? "Scratch exceeds 64 nested containers"
                : "script data exceeds 64 nested containers");
        }
        if (value.isJsonNull()) {
            return Starlark.NONE;
        }
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (mode != Mode.FACILITY && object.size() == 1 && object.has("$text")) {
                if (mode == Mode.SCRATCH) {
                    throw Starlark.errorf("$text is reserved for opaque text references");
                }
                if (!texts.isEmpty()) {
                    return new Text(texts.get(object.get("$text").getAsInt()));
                }
            }
            Dict<String, Object> result = Dict.of(mutability);
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (thread != null) {
                    ScriptSteps.charge(thread, entry.getKey().length());
                }
                result.putEntry(entry.getKey(), read(entry.getValue(), texts, mutability, depth + 1, mode, thread));
            }
            return result;
        }
        if (value.isJsonArray()) {
            List<Object> result = new ArrayList<>();
            for (JsonElement entry : value.getAsJsonArray()) {
                result.add(read(entry, texts, mutability, depth + 1, mode, thread));
            }
            return StarlarkList.copyOf(mutability, result);
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (thread != null) {
            ScriptSteps.charge(thread, primitive.getAsString().length());
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        BigDecimal number = primitive.getAsBigDecimal();
        if (mode == Mode.FACILITY) {
            return number.scale() == 0
                ? StarlarkInt.of(number.toBigIntegerExact()) : StarlarkFloat.of(number.doubleValue());
        }
        if (mode == Mode.SCRATCH && (number.scale() != 0 || primitive.getAsString().contains("e")
                || primitive.getAsString().contains("E"))) {
            double decoded = primitive.getAsDouble();
            if (!Double.isFinite(decoded)) {
                throw Starlark.errorf("Scratch floats must be finite");
            }
            return StarlarkFloat.of(decoded);
        }
        return number.stripTrailingZeros().scale() <= 0 ? StarlarkInt.of(number.toBigIntegerExact())
                : StarlarkFloat.of(number.doubleValue());
    }

    public JsonElement write(Object value) throws EvalException {
        return write(value, new IdentityHashMap<>(), 0);
    }

    private JsonElement write(Object value, IdentityHashMap<Object, Boolean> seen, int depth) throws EvalException {
        ScriptRuntime.checkTime();
        count(mode == Mode.OUTPUT ? 8 : 1);
        if (depth > MAX_DEPTH || seen.containsKey(value)) {
            throw Starlark.errorf("Output contains a cycle or exceeds 64 nested containers");
        }
        if (value == Starlark.NONE) {
            return JsonNull.INSTANCE;
        }
        if (mode != Mode.FACILITY && value instanceof Text text) {
            if (mode == Mode.SCRATCH) {
                throw Starlark.errorf("Scratch accepts plain data only; opaque text cannot be stored");
            }
            JsonObject ref = new JsonObject();
            ref.addProperty("$text", texts.size());
            texts.add(text.words);
            return ref;
        }
        if (value instanceof String string) {
            count(string.length());
            return new JsonPrimitive(string);
        }
        if (value instanceof Boolean bool) {
            return new JsonPrimitive(bool);
        }
        if (value instanceof StarlarkInt integer) {
            count(integer.toString().length());
            return new JsonPrimitive(integer.toBigInteger());
        }
        if (value instanceof StarlarkFloat number) {
            if (mode != Mode.OUTPUT && !Double.isFinite(number.toDouble())) {
                throw Starlark.errorf(mode == Mode.SCRATCH ? "Scratch floats must be finite"
                    : "state and action arguments must use finite numbers");
            }
            return new JsonPrimitive(number.toDouble());
        }
        seen.put(value, true);
        try {
            if (value instanceof Map<?, ?> map) {
                if (mode != Mode.FACILITY && map.size() == 1 && map.containsKey("$text")) {
                    throw Starlark.errorf("$text is reserved for opaque text references");
                }
                JsonObject result = new JsonObject();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) {
                        throw Starlark.errorf(mode == Mode.FACILITY
                            ? "state and action arguments must use string dict keys" : "Output dict keys must be strings");
                    }
                    count(key.length());
                    result.add(key, write(entry.getValue(), seen, depth + 1));
                }
                return result;
            }
            if (value instanceof Iterable<?> entries
                    && (mode == Mode.OUTPUT || value instanceof StarlarkList<?> || value instanceof Tuple)) {
                JsonArray result = new JsonArray();
                for (Object entry : entries) {
                    result.add(write(entry, seen, depth + 1));
                }
                return result;
            }
            if (mode == Mode.SCRATCH) {
                throw Starlark.errorf("Cannot store %s in scratch; use plain data", Starlark.type(value));
            }
            if (mode == Mode.FACILITY) {
                throw Starlark.errorf("state and action arguments must use plain data");
            }
            throw Starlark.errorf("Cannot output %s; output plain values or text", Starlark.type(value));
        } finally {
            seen.remove(value);
        }
    }

    public static ScriptValues facility() {
        return new ScriptValues(Mode.FACILITY, FACILITY_BYTES);
    }

    /** Recipe catalogs have their own size limit so a native recipe type can be filtered in Starlark. */
    public static ScriptValues facilityRecipes() {
        return new ScriptValues(Mode.FACILITY, 512 * 1024);
    }

    public static Object readFacility(JsonElement data, Mutability scope) throws EvalException {
        return read(data, List.of(), scope, 0, Mode.FACILITY, null);
    }

    public static String encodeFacility(JsonElement data) {
        String encoded = data.toString();
        if (encoded.getBytes(StandardCharsets.UTF_8).length > FACILITY_BYTES) {
            throw new ScriptFault(ScriptFault.Kind.RESULT_SIZE, "encoded state exceeds " + FACILITY_BYTES + " bytes",
                null);
        }
        return encoded;
    }

    public <T> Dict<String, Object> snapshot(Map<String, T> values, Function<T, Object> adapt, Mutability scope)
            throws EvalException {
        Dict<String, Object> result = Dict.of(scope);
        for (var entry : values.entrySet()) {
            count(entry.getKey().length());
            result.putEntry(entry.getKey(), snapshotValue(adapt.apply(entry.getValue()), scope, 0));
        }
        return result;
    }

    private Object snapshotValue(Object value, Mutability scope, int depth) throws EvalException {
        ScriptRuntime.checkTime();
        if (depth > MAX_DEPTH) {
            throw Starlark.errorf("script data exceeds %d nested containers", MAX_DEPTH);
        }
        count(1);
        return switch (value) {
            case Boolean bool -> bool;
            case String text -> {
                count(text.length());
                yield text;
            }
            case Number number -> {
                double real = number.doubleValue();
                yield real == Math.rint(real) && Math.abs(real) < 0x1p63
                    ? StarlarkInt.of((long) real) : StarlarkFloat.of(real);
            }
            case List<?> list -> {
                List<Object> converted = new ArrayList<>();
                for (Object item : list) {
                    converted.add(snapshotValue(item, scope, depth + 1));
                }
                yield StarlarkList.copyOf(scope, converted);
            }
            case Map<?, ?> map -> {
                Dict<String, Object> converted = Dict.of(scope);
                for (var entry : map.entrySet()) {
                    converted.putEntry((String) entry.getKey(), snapshotValue(entry.getValue(), scope, depth + 1));
                }
                yield converted;
            }
            case null, default -> throw Starlark.errorf("invalid snapshot value");
        };
    }

    private void count(int characters) {
        if (characters > limit - size) {
            throw mode == Mode.FACILITY
                ? new ScriptFault(ScriptFault.Kind.RESULT_SIZE, "script data exceeds " + limit + " characters", null)
                : new ScriptFault(ScriptFault.Kind.RESULT_SIZE, "Output exceeds " + (limit / 1024) + " KiB", null);
        }
        size += characters;
    }

    public List<Component> texts() {
        return List.copyOf(texts);
    }
}
