package io.github.izakyl.folkways.raid;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

final class RaidDefinitions extends SimpleJsonResourceReloadListener {

    static final RaidDefinitions INSTANCE = new RaidDefinitions();
    private Map<ResourceLocation, RaidDefinition> definitions = Map.of();

    private RaidDefinitions() {
        super(new Gson(), "raid");
    }

    @Override
    protected void apply(
        Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RaidDefinition> parsed = new LinkedHashMap<>();
        resources.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(
            entry -> {
                try {
                    parsed.put(entry.getKey(), RaidDefinition.parse(entry.getValue().toString()));
                } catch (RuntimeException exception) {
                    LogUtils.getLogger().error("Invalid raid {}", entry.getKey(), exception);
                }
            });
        definitions = Map.copyOf(parsed);
    }

    RaidDefinition get(ResourceLocation id) {
        return definitions.get(id);
    }

    RaidDefinition first() {
        return definitions.entrySet().stream().min(Map.Entry.comparingByKey()).map(Map.Entry::getValue).orElse(null);
    }
}
