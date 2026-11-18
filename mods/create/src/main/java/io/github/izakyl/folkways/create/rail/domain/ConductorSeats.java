package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.work.api.Stances;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

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

    static Collection<Cab> occupiedCabs(MinecraftServer server, TransitNetwork network, UUID train, UUID colony) {
        Map<SeatRef, Cab> occupied = new LinkedHashMap<>();
        for (boolean forward : new boolean[] {true, false}) {
            for (SeatRef seat : network.conductorSeats(server, train, forward)) {
                if (occupied.containsKey(seat)) {
                    continue;
                }
                Optional<UUID> occupant = network.occupant(server, seat);
                if (occupant.isEmpty()) {
                    continue;
                }
                network.seatPosition(server, seat).ifPresent(at -> at.level(server)
                    .map(level -> level.getEntity(occupant.get()))
                    .filter(Entity::isAlive)
                    .filter(entity -> !(entity instanceof Player))
                    .flatMap(Bodies::of)
                    .filter(body -> body.colonyId().filter(colony::equals).isPresent())
                    .ifPresent(body -> occupied.put(seat,
                        new Cab(train, seat, forward, at, Stances.WHEREVER, Optional.of(body.id())))));
            }
        }
        return occupied.values();
    }

    void goals(ServerLevel level, List<Grown> into) {
        Map<SeatRef, Cab> wanted = new LinkedHashMap<>(boarding);
        open.forEach(wanted::putIfAbsent);
        for (Cab cab : wanted.values()) {
            if (cab.at().in(level)) {
                ConductNode conduct = new ConductNode(idOf(cab, "conduct"), trade, cab, this);
                if (cab.driver().isPresent() || seated.contains(cab.seat())) {
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
        return UUID.nameUUIDFromBytes(("folkways_create:rail/" + train + "/" + cab.seat() + "/"
                + rides.getOrDefault(cab.seat(), 0) + "/" + step)
            .getBytes(StandardCharsets.UTF_8));
    }
}
