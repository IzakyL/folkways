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
            if (!legacy.equals(id.toString()) && NamespaceIds.current(legacy).equals(id.toString())) {
                alias(registry, legacy, id.toString());
            }
        }
        registry.register(bus);
    }

    public static void alias(DeferredRegister<?> registry, String oldId, String currentId) {
        registry.addAlias(ResourceLocation.parse(oldId), ResourceLocation.parse(currentId));
    }
}
