package io.github.izakyl.folkways.vanilla.person.living;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventoryDiscovery;
import io.github.izakyl.folkways.core.inventory.api.InventoryProviders;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;

final class DiningInventory implements InventoryProviders.Provider {
    static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "dining_tables");
    private record Key(UUID colony, ResourceKey<Level> dimension) { }
    private record Membership(long revision, List<WorldPos> positions, boolean complete) { }
    private static final Map<Key, Membership> MEMBERS = new HashMap<>();

    static void update(Colony colony, ServerLevel level, List<WorldPos> positions, boolean complete) {
        Key key = new Key(colony.id(), level.dimension());
        Membership old = MEMBERS.get(key);
        if (old != null && old.positions.equals(positions) && old.complete == complete) { return; }
        MEMBERS.put(key, new Membership(old == null ? 1 : old.revision + 1, List.copyOf(positions), complete));
        colony.inventoryFeedback().providersChanged(ID);
    }

    static void forget(Colony colony) {
        MEMBERS.keySet().removeIf(key -> key.colony.equals(colony.id()));
    }

    @Override
    public long revision(InventoryDiscovery colony, ServerLevel level) {
        Membership membership = MEMBERS.get(new Key(colony.id(), level.dimension()));
        return membership == null ? 0 : membership.revision;
    }

    @Override
    public InventoryProviders.Page discover(InventoryDiscovery colony, ServerLevel level, String cursor, int limit) {
        Membership membership = MEMBERS.get(new Key(colony.id(), level.dimension()));
        List<WorldPos> positions = membership == null ? List.of() : membership.positions;
        int start = cursor == null ? 0 : Integer.parseInt(cursor);
        int end = Math.min(start + limit, positions.size());
        return new InventoryProviders.Page(positions.subList(start, end).stream()
            .map(at -> new InventoryProviders.Endpoint(ID, at)).toList(),
            end == positions.size() ? null : Integer.toString(end), membership == null || membership.complete);
    }

    @Override
    public Container open(ServerLevel level, InventoryProviders.Endpoint endpoint) {
        return DiningTables.inventory(level, endpoint.anchor().block(level)).orElse(null);
    }
}
