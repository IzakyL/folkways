package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.work.Grown;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;

final class ConductorSeats {

    private final UUID train;
    private final Runnable changed;
    private final Vocation trade = RailContent.trade();

    private final Map<SeatRef, Cab> boarding = new ConcurrentHashMap<>();
    // Seats a conductor has taken, and drives from until it leaves them.
    private final Set<SeatRef> seated = ConcurrentHashMap.newKeySet();
    // How many times each seat has been left: a seat left is a new ride, taken and driven afresh.
    private final Map<SeatRef, Integer> rides = new ConcurrentHashMap<>();

    private volatile Map<SeatRef, Cab> open = Map.of();
    private volatile int seatTicks = Boarding.seatTicks();

    ConductorSeats(UUID train) {
        this(train, () -> { });
    }

    ConductorSeats(UUID train, Runnable changed) {
        this.train = train;
        this.changed = changed;
    }

    void reconcile(Collection<Cab> found) {
        seatTicks = Boarding.seatTicks();
        Map<SeatRef, Cab> next = new LinkedHashMap<>();
        for (Cab cab : found) {
            next.put(cab.seat(), cab);
        }
        open = Map.copyOf(next);
    }

    void goals(ServerLevel level, List<Grown> into) {
        Map<SeatRef, Cab> wanted = new LinkedHashMap<>(boarding);
        open.forEach(wanted::putIfAbsent);
        for (Cab cab : wanted.values()) {
            if (cab.at().in(level)) {
                ConductNode conduct = new ConductNode(idOf(cab, "conduct"), trade, cab, this);
                if (seated.contains(cab.seat())) {
                    into.add(Grown.of(conduct));
                } else {
                    TakeSeatNode take = new TakeSeatNode(idOf(cab, "take"), trade, cab, seatTicks, this);
                    into.add(Grown.then(take, conduct).byOneWorker());
                }
            }
        }
    }

    void boarding(Cab cab) {
        boarding.put(cab.seat(), cab);
        changed.run();
    }

    void seated(SeatRef seat) {
        seated.add(seat);
    }

    void left(SeatRef seat) {
        seated.remove(seat);
        rides.merge(seat, 1, Integer::sum);
        boarding.remove(seat);
        changed.run();
    }

    private UUID idOf(Cab cab, String step) {
        return UUID.nameUUIDFromBytes(("folkways:rail/" + train + "/" + cab.seat() + "/"
                + rides.getOrDefault(cab.seat(), 0) + "/" + step)
            .getBytes(StandardCharsets.UTF_8));
    }
}
