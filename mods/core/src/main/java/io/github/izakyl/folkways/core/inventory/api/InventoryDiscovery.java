package io.github.izakyl.folkways.core.inventory.api;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public interface InventoryDiscovery {

    UUID id();

    <T> Optional<T> service(ResourceLocation owner, Class<T> type);
}
