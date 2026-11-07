package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.inventory.api.InventoryDiscovery;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface InventorySources extends InventoryDiscovery {

    long inventoryMembershipRevision();

    Collection<Resident> residents();

    Collection<WorldPos> storesIn(ResourceKey<Level> dimension);

    Collection<WorldPos> withheldIn(ResourceKey<Level> dimension);

    Collection<Workshop> workshopsIn(ResourceKey<Level> dimension);
}
