package io.github.izakyl.folkways.core.base.persist.upgrade;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Register renamed ids during mod construction, before NeoForge's RegisterEvent. */
public final class RegistryAliases {
    private RegistryAliases() {
    }

    public static void register(DeferredRegister<?> registry, IEventBus bus) {
        for (var entry : registry.getEntries()) {
            ResourceLocation id = entry.getId();
            String legacy = "folkways:" + id.getPath();
            // The candidate is inferred from the current path, so it may never have been a released ID.
            if (NamespaceIds.hasMapping(legacy) && NamespaceIds.current(legacy).equals(id.toString())) {
                alias(registry, legacy, id);
            }
        }
        registry.register(bus);
    }

    public static void alias(DeferredRegister<?> registry, String oldId, ResourceLocation currentId) {
        registry.addAlias(ResourceLocation.parse(oldId), currentId);
    }
}
