package io.github.izakyl.folkways.core.travel.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public interface Passage {

    ResourceLocation id();

    default List<Hop> hopsIn(Colony colony) {
        return List.of();
    }

    default boolean through(Hop first, Hop next) {
        return first.service().equals(next.service());
    }

    // One ride standing in for two the plan takes back to back without getting off, from the first's boarding to the
    // next's landing.
    default Hop joined(Hop first, Hop next) {
        return new Hop(first.service(), first.boarding(), next.landing(), Fare.then(first.fare(), next.fare()));
    }

    Conveyance aboard(Hop hop);

    /** A walking passage uses the traveller's own locomotion. */
    default Conveyance aboard(Hop hop, Locomotion moving, double speed, RefusalKind lost) {
        return aboard(hop);
    }
}
