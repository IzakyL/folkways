package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import io.github.izakyl.folkways.plugins.rail.domain.RailPresence;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;

public final class RailPassage implements Passage {

    private static final org.slf4j.Logger LOGGER =
        org.slf4j.LoggerFactory.getLogger("folkways-labor");

    private int lastLaid = -1;

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath("folkways", "train");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public List<Hop> hopsIn(Colony colony) {
        RailPresence trains =
            RailContent.presenceIn(colony.service(RailContent.ID, Object.class)).orElse(null);
        if (trains == null) {
            return List.of();
        }
        List<Hop> hops = new ArrayList<>();
        for (RailLegs.Leg leg : RailLegs.of(trains.seen())) {
            hops.add(leg.hop());
        }
        int laid = hops.size();
        if (laid != lastLaid) {
            lastLaid = laid;
            LOGGER.info("rail hops={} lines={}", laid, trains.seen().lines().size());
        }
        return List.copyOf(hops);
    }

    // Staying aboard the same train from one stop's run into the next, so long as it does not bring the rider back to
    // where they got on.
    @Override
    public boolean through(Hop first, Hop next) {
        return first.fare() instanceof RailFare one && next.fare() instanceof RailFare two
            && one.train().equals(two.train()) && one.alight().equals(two.board()) && !one.board().equals(two.alight());
    }

    // Boarded where the first got on and for the run it caught, set down where the next gets off.
    @Override
    public Hop joined(Hop first, Hop next) {
        RailFare one = (RailFare) first.fare();
        RailFare two = (RailFare) next.fare();
        return new Hop(first.service(), first.boarding(), next.landing(),
            new RailFare(one.train(), one.runs(), one.board(), two.alight(), one.run()));
    }

    @Override
    public Conveyance aboard(Hop hop) {
        if (!(hop.fare() instanceof RailFare fare)) {
            throw new IllegalArgumentException(hop.service() + " is not a train run of ours");
        }
        LOGGER.info("rail aboard train={} {}->{}", fare.train(), fare.board(), fare.alight());
        return new RailConveyance(new RailLegs.Leg(fare.train(), fare.board(), fare.alight(),
            hop.boarding(), hop.landing(), fare.runs(), fare.run()));
    }
}
