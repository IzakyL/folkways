package io.github.izakyl.folkways.core.script.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

final class ScriptStd {

    private static final String NAMESPACE = "folkways_std";
    private static final Map<String, String> SOURCES = Map.of(
        "math", read("math"), "text", read("text"), "ik", read("ik"), "sweep", read("sweep"),
        "_sweep_path", read("_sweep_path"), "position", read("position"), "ballistics", read("ballistics"),
        "_ballistics", read("_ballistics"));

    private ScriptStd() {
    }

    static ResourceLocation resolve(String label) {
        String name = label.substring("@std//".length());
        if (!SOURCES.containsKey(name)) {
            return null;
        }
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, name);
    }

    static boolean isStandard(ResourceLocation id) {
        return id.getNamespace().equals(NAMESPACE);
    }

    /** Modules that issue routine actions, such as write_sequence, and so load only where those are bound. */
    static boolean routineOnly(ResourceLocation id) {
        return isStandard(id) && (id.getPath().equals("sweep") || id.getPath().equals("_sweep_path")
            || id.getPath().equals("position") || id.getPath().equals("ballistics"));
    }

    static Optional<String> source(ResourceLocation id) {
        return isStandard(id) ? Optional.ofNullable(SOURCES.get(id.getPath())) : Optional.empty();
    }

    static Map<String, Object> bindings(ResourceLocation id) {
        if (!isStandard(id)) {
            return Map.of();
        }
        return switch (id.getPath()) {
            case "math" -> Map.of("_math", StdMath.INSTANCE);
            case "ik" -> Map.of("_ik", StdIk.INSTANCE);
            case "_sweep_path" -> Map.of("_sweep", StdSweep.INSTANCE);
            default -> StdBindings.of(id.getPath());
        };
    }

    private static String read(String name) {
        try (var source = ScriptStd.class.getResourceAsStream("/assets/folkways_core/std/" + name + ".star")) {
            if (source == null) {
                throw new IllegalStateException("Missing built-in script module " + name);
            }
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot read built-in script module " + name, failed);
        }
    }
}
