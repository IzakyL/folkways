package io.github.izakyl.folkways.core.script.api;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Java builtins that other packages bind into a built-in `@std` module, so the script runtime names none of them.
 * The module's Starlark source still ships with the standard library; only its native half is bound here.
 */
public final class StdBindings {

    private static final Map<String, Map<String, Object>> BOUND = new ConcurrentHashMap<>();

    private StdBindings() {
    }

    /** Binds `value` as global `name` in the built-in module `module`, such as `_ballistics`. */
    public static void bind(String module, String name, Object value) {
        Objects.requireNonNull(value, "value");
        if (BOUND.putIfAbsent(module, Map.of(name, value)) != null) {
            throw new IllegalStateException(module + " already has its builtins bound");
        }
    }

    static Map<String, Object> of(String module) {
        return BOUND.getOrDefault(module, Map.of());
    }
}
