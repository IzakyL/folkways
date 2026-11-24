package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Entry;
import io.github.izakyl.folkways.core.facility.api.FacilityRidePassage.RideFare;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class FacilityRides {

    enum State {
        WAITING, RIDING, ARRIVED, FAILED
    }

    static final class Ticket {
        final RideFare fare;
        final Hop hop;
        final Mob body;
        long deadline;
        State state = State.WAITING;

        Ticket(RideFare fare, Hop hop, Mob body, long now) {
            this.fare = fare;
            this.hop = hop;
            this.body = body;
            deadline = now + patience(fare);
        }
    }

    private final List<Ticket> waiting = new ArrayList<>();
    private List<Ticket> riding = List.of();
    private UUID run;
    private boolean done;

    static long patience(RideFare fare) {
        return Math.max(40L, 2L * fare.ride().ticks());
    }

    Ticket queue(RideFare fare, Hop hop, Mob body, long now) {
        Ticket ticket = new Ticket(fare, hop, body, now);
        waiting.add(ticket);
        return ticket;
    }

    boolean owns(UUID id) {
        return id.equals(run);
    }

    void ended(UUID id, boolean success) {
        if (owns(id)) {
            run = null;
            done = success;
            if (!success) {
                riding.forEach(ticket -> ticket.state = State.FAILED);
            }
        }
    }

    void tick(DefinedFacilities service, Entry entry, ServerLevel level, boolean loaded) {
        long now = level == null ? Long.MAX_VALUE : level.getGameTime();
        waiting.removeIf(ticket -> {
            if (!loaded || expired(ticket, level, now)
                    || !RideStances.standingIn(ticket.body, ticket.hop.boarding()) || !ticket.fare.available(level)) {
                ticket.state = State.FAILED;
                return true;
            }
            return false;
        });
        for (Ticket ticket : riding) {
            if (!loaded || expired(ticket, level, now)) {
                ticket.state = State.FAILED;
            } else if (RideStances.standingIn(ticket.body, ticket.hop.landing())) {
                if (done) {
                    ticket.state = State.ARRIVED;
                }
            } else if (!inside(entry.facility, level, ticket.body)) {
                ticket.state = State.FAILED;
            }
        }
        if (riding.stream().anyMatch(ticket -> ticket.state == State.RIDING)) {
            return;
        }
        if (run != null) {
            service.cancelRide(entry, run);
        }
        riding = List.of();
        for (Ticket first : waiting) {
            RideFare fare = first.fare;
            List<Ticket> batch = waiting.stream().filter(ticket -> fare.equals(ticket.fare))
                .limit(fare.ride().capacity()).toList();
            Map<String, Value> arguments = Map.of("from", new Value.Text(fare.from()), "to", new Value.Text(fare.to()),
                "riders", new Value.Sequence(batch.stream().<Value>map(ticket ->
                    new Value.Text(ticket.body.getUUID().toString())).toList()));
            if (DefinedFacilities.busy(entry, fare.ride().routine(), arguments, null)) {
                continue;
            }
            waiting.removeAll(batch);
            riding = batch;
            done = false;
            batch.forEach(ticket -> {
                ticket.state = State.RIDING;
                ticket.deadline = now + patience(fare);
            });
            service.startRide(entry, level, fare.ride().routine(), arguments, this);
            return;
        }
    }

    void started(UUID id) {
        run = id;
    }

    void completed(boolean success) {
        done = success;
        if (!success) {
            riding.forEach(ticket -> ticket.state = State.FAILED);
        }
    }

    private static boolean expired(Ticket ticket, ServerLevel level, long now) {
        return ticket.state == State.FAILED || !ticket.body.isAlive()
            || ticket.body.level() != level || now >= ticket.deadline;
    }

    private static boolean inside(FacilityDefinition facility, ServerLevel level, Mob body) {
        if (body.getVehicle() != null && facility.extent().contains(body.getVehicle().getUUID())) {
            return true;
        }
        for (FacilityExtent.Element element : facility.extent().elements()) {
            Realm realm = switch (element) {
                case FacilityExtent.Block block -> block.pos().realm();
                case FacilityExtent.Box box -> box.realm();
                case FacilityExtent.Entity ignored -> null;
            };
            if (realm != null && WorldSpaces.relative(level, realm, body.position())
                    .map(feet -> facility.extent().contains(new WorldPos(realm, BlockPos.containing(feet))))
                    .orElse(false)) {
                return true;
            }
        }
        return false;
    }
}
