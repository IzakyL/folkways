package io.github.izakyl.folkways.core.transport.api;

import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

// Identity includes custody: co-located interfaces need an explicit link to exchange goods.
public record TransportEndpoint(ResourceLocation id, WorldPos at, Set<Stand> stands,
                                Handover handover) {

    // PACK: handed over in a worker's pack, to or from a node. Every line's ends are PACK: it loads through a node
    // of its own and hands over through another, and keeps what it carries in its own carrier meanwhile, never in
    // the colony's stores. INVENTORY: a store a request takes from or puts into, only ever at a request's own end.
    // PARCEL: inside a mode's own carrier.
    public enum Form { PACK, INVENTORY, PARCEL }

    public record Handover(Form form, ResourceLocation custody) {
    }

    public TransportEndpoint {
        stands = Set.copyOf(stands);
    }
}
