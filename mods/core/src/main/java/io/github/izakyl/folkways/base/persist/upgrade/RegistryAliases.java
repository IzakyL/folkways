package io.github.izakyl.folkways.base.persist.upgrade;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Register renamed ids during mod construction, before NeoForge's RegisterEvent. */
public final class RegistryAliases {
    private RegistryAliases() {
    }

    public static void alias(DeferredRegister<?> registry, String oldId, String currentId) {
        registry.addAlias(ResourceLocation.parse(oldId), ResourceLocation.parse(currentId));
    }
}
