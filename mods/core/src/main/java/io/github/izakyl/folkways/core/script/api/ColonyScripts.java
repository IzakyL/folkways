package io.github.izakyl.folkways.core.script.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;

public final class ColonyScripts {

    private static final ResourceLocation ID = ResourceLocation.parse("folkways_core:scripts");

    private ColonyScripts() {
    }

    public static boolean validName(String name) {
        return name.matches("[a-z0-9_.-]{1,128}");
    }

    public static Map<String, String> snapshot(Colony colony) {
        var bag = colony.kept(ID);
        Map<String, String> scripts = new LinkedHashMap<>();
        bag.getAllKeys().forEach(name ->
            scripts.put(name, new String(bag.getByteArray(name), StandardCharsets.UTF_8)));
        return Map.copyOf(scripts);
    }

    public static void write(Colony colony, String name, String source) {
        var bag = colony.kept(ID);
        if (source == null) {
            bag.remove(name);
        } else {
            bag.putByteArray(name, source.getBytes(StandardCharsets.UTF_8));
        }
        colony.keep(ID, bag);
    }

    public static String name(ResourceLocation id) {
        return id.getPath().substring("scripts/".length());
    }
}
