package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.passage.Passage;
import io.github.izakyl.folkways.core.api.resident.Conveyance;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public final class ElevatorPassage implements Passage {

    @Override
    public ResourceLocation id() {
        return ElevatorPlugin.ID;
    }

    @Override
    public List<Hop> hopsIn(Colony colony) {
        var server = ServerLifecycleHooks.getCurrentServer();
        Elevators lifts = colony.service(ElevatorPlugin.ID, Elevators.class).orElse(null);
        if (server == null || lifts == null) {
            return List.of();
        }
        List<Hop> hops = new ArrayList<>();
        for (ElevatorRef lift : lifts.all()) {
            ElevatorController controller = ElevatorDispatch.controller(server, colony.id(), lift).orElse(null);
            ElevatorNetwork.View view = controller == null ? null : controller.view().orElse(null);
            if (view == null || !view.powered() || view.places().isEmpty() || controller.retired()) {
                continue;
            }
            // The lift need not be here now, and being full does not remove a floor from the network.
            for (ElevatorNetwork.Floor from : view.floors()) {
                for (ElevatorNetwork.Floor to : view.floors()) {
                    if (from.y() == to.y()) {
                        continue;
                    }
                    hops.add(new Hop(lift.service(), from.waiting(), to.platform(),
                        new ElevatorFare(colony.id(), lift, from.y(), to.y(), controller.forecast())));
                }
            }
        }
        return List.copyOf(hops);
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
