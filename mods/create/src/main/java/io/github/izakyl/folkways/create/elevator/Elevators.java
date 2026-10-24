package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Holding;
import io.github.izakyl.folkways.core.api.terms.Realm;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

// The colony's ordinary block holdings persist ownership; this service only maintains their live controllers.
public final class Elevators {

    private final Colony colony;
    private final Set<ElevatorRef> lifts = new LinkedHashSet<>();
    private final Map<UUID, ElevatorRef> located = new HashMap<>();
    private MinecraftServer server;

    public Elevators(Colony colony) {
        this.colony = colony;
    }

    public List<ElevatorRef> all() {
        return List.copyOf(lifts);
    }

    public void tick(MinecraftServer server) {
        this.server = server;
        Set<ElevatorRef> wanted = new LinkedHashSet<>();
        Set<UUID> held = new LinkedHashSet<>();
        for (Holding holding : colony.holdings(ElevatorPlugin.ID)) {
            if (!(holding.what() instanceof Held.Block block)
                    || !(block.cell().realm() instanceof Realm.Dimension dimension)) {
                continue;
            }
            held.add(holding.id());
            var level = server.getLevel(dimension.id());
            ElevatorRef ref = new ElevatorRef(dimension.id(), block.cell().cell());
            if (level != null && level.hasChunkAt(ref.pulley())) {
                ref = new ElevatorRef(dimension.id(), ElevatorPlugin.mainPulley(level, ref.pulley()));
            } else {
                ref = located.getOrDefault(holding.id(), ref);
            }
            located.put(holding.id(), ref);
            wanted.add(ref);
        }
        located.keySet().retainAll(held);
        for (ElevatorRef lift : List.copyOf(lifts)) {
            if (!wanted.contains(lift)) {
                ElevatorDispatch.release(server, colony.id(), lift);
                lifts.remove(lift);
            }
        }
        for (ElevatorRef lift : wanted) {
            if (ElevatorDispatch.claim(server, colony.id(), lift)) {
                lifts.add(lift);
            }
        }
    }

    public void closed(Closing why) {
        if (server != null) {
            for (ElevatorRef lift : lifts) {
                ElevatorDispatch.release(server, colony.id(), lift);
            }
        }
        lifts.clear();
        located.clear();
    }
}
