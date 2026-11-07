package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.core.inventory.api.InventoryDiscovery;
import io.github.izakyl.folkways.core.inventory.api.InventoryProviders;
import io.github.izakyl.folkways.core.item.api.Containers;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;

final class DispatchInventory implements InventoryProviders.Provider {

    @Override
    public long revision(InventoryDiscovery colony, ServerLevel level) {
        return colony.service(DispatchContent.ID, DispatchPresence.class)
            .map(DispatchPresence::inventoryRevision).orElse(0L);
    }

    @Override
    public InventoryProviders.Page discover(InventoryDiscovery colony, ServerLevel level, String cursor, int limit) {
        var endpoints = colony.service(DispatchContent.ID, DispatchPresence.class)
            .map(dispatch -> dispatch.endpoints(level.dimension())).orElse(List.of());
        int start = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(endpoints.size(), start + limit);
        boolean complete = colony.service(DispatchContent.ID, DispatchPresence.class)
            .map(dispatch -> dispatch.inventoryComplete(level.dimension())).orElse(true);
        return new InventoryProviders.Page(endpoints.subList(start, end),
            end == endpoints.size() ? null : Integer.toString(end), complete);
    }

    @Override
    public Container open(ServerLevel level, InventoryProviders.Endpoint endpoint) {
        var at = endpoint.anchor().block(level);
        return Containers.at(level, at).orElseGet(() -> PackageNetworks.get().inventoryAt(level, at));
    }
}
