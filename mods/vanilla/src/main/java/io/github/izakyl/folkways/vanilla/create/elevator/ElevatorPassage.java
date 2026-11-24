package io.github.izakyl.folkways.vanilla.create.elevator;

import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import net.minecraft.resources.ResourceLocation;

public final class ElevatorPassage implements Passage {

    @Override
    public ResourceLocation id() {
        return ElevatorPlugin.ID;
    }

    @Override
    public boolean through(Hop first, Hop next) {
        // All pairs of floors are offered directly; no itinerary needs intermediate floor transfers.
        return false;
    }

    @Override
    public Conveyance aboard(Hop hop) {
        if (!(hop.fare() instanceof ElevatorFare fare)) {
            throw new IllegalArgumentException(hop.service() + " is not an elevator ride of ours");
        }
        return new ElevatorConveyance(fare, hop.boarding(), hop.landing());
    }
}
