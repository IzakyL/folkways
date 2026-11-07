package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.vocation.api.Vocation;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

// Settings are resolved on the server thread; planners only carry these immutable item sets.
public record ToolPolicy(Map<ResourceLocation, Set<Item>> tools) {
    public ToolPolicy {
        Map<ResourceLocation, Set<Item>> copy = new LinkedHashMap<>();
        tools.forEach((id, items) -> copy.put(id, Set.copyOf(items)));
        tools = Map.copyOf(copy);
    }

    public boolean allows(Optional<Vocation> vocation, Item tool) {
        return vocation.isEmpty() || tools.getOrDefault(vocation.get().id(), Set.of()).contains(tool);
    }
}
