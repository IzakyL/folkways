package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.combat.api.AirportTraffic;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic.Priority;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic.Resource;
import io.github.izakyl.folkways.core.combat.api.AirportTraffic.State;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/** Single authority for airport surfaces; leases and queue order are deliberately never persisted. */
public final class AirTraffic {
    private static final Map<ServerLevel, AirTraffic> DIMENSIONS = new IdentityHashMap<>();
    private final List<Booking> bookings = new ArrayList<>();

    private AirTraffic() {
    }

    public static AirTraffic of(ServerLevel level) {
        return DIMENSIONS.computeIfAbsent(level, ignored -> new AirTraffic());
    }

    boolean free(List<Resource> resources) {
        return bookings.stream().noneMatch(booking -> resources.stream()
            .anyMatch(resource -> booking.resources.stream().anyMatch(resource::overlaps)));
    }

    public AirportTraffic.Claim request(Priority priority, List<Resource> resources) {
        var booking = new Booking(List.copyOf(resources));
        bookings.add(booking);
        advance();
        return booking;
    }

    private void advance() {
        var earlier = new ArrayList<Booking>();
        for (var booking : bookings) {
            if (booking.state == State.WAITING && earlier.stream().noneMatch(booking::overlaps)
                    && bookings.stream().noneMatch(other -> other != booking
                        && other.state == State.HELD && booking.overlaps(other))) {
                booking.state = State.HELD;
            }
            earlier.add(booking);
        }
    }

    private final class Booking implements AirportTraffic.Claim {
        private final List<Resource> resources;
        private State state = State.WAITING;

        private Booking(List<Resource> resources) {
            this.resources = resources;
        }

        private boolean overlaps(Booking other) {
            return resources.stream().anyMatch(a -> other.resources.stream().anyMatch(a::overlaps));
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void close() {
            if (state == State.RELEASED || state == State.REVOKED) {
                return;
            }
            state = State.RELEASED;
            bookings.remove(this);
            advance();
        }
    }

    private void clear() {
        var previous = List.copyOf(bookings);
        bookings.clear();
        previous.forEach(booking -> booking.state = State.REVOKED);
    }

    static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            var traffic = DIMENSIONS.remove(level);
            if (traffic != null) {
                traffic.clear();
            }
        }
    }

    static void stop(ServerStoppingEvent event) {
        var previous = List.copyOf(DIMENSIONS.values());
        DIMENSIONS.clear();
        previous.forEach(AirTraffic::clear);
    }
}
