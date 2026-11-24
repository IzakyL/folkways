package io.github.izakyl.folkways.core.testing;

import com.google.gson.Gson;
import java.util.Map;

/** A facility routine that lays, loads and fires a gun with {@code @std//ballistics}, as a player would write it. */
public final class ShootRoutine {

    public static final String SOURCE = """
        load("@std//ballistics", "shoot_step", "shoot_path", "shoot_readings", "shoot_check")

        def step(state, readings, args):
            return shoot_step(state, readings, args)

        def path(readings, args):
            return shoot_path(readings, args)

        def readings(readings, args):
            return shoot_readings(readings, args)

        def check(args):
            return shoot_check(args)
        """;

    private ShootRoutine() {
    }

    /** The routine's definition entry with these default arguments. */
    public static Map<String, Object> with(Map<String, Object> arguments) {
        return Map.of("source", SOURCE, "with", arguments);
    }

    /** The source as a Starlark string literal, for a definition written as an assistant script. */
    public static String literal() {
        return new Gson().toJson(SOURCE);
    }
}
