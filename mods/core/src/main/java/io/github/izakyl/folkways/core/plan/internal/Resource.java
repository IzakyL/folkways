package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public sealed interface Resource {

    record Transport(ResourceLocation resource, ResourceLocation unit) implements Resource {
    }


    record Lot(Stash where, ItemSpec spec) implements Resource {
    }

    // Future stock booked by a storage trunk; unlike a Lot, it never credits free room.
    record Transit(Stash where, ItemSpec spec) implements Resource {
    }

    record Space(Stash where, ItemSpec spec) implements Resource {
    }

    record Cargo(UUID resident, ItemSpec spec) implements Resource {
    }

    // Goods the node `by` will put into a pack, before anyone knows whose: claimed by the step that takes them out
    // again, and turned into that resident's Cargo once `by` is done.
    record Handed(UUID by, ItemSpec spec) implements Resource {
    }
}
