package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.Locomotion;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.travel.api.Fare;
import io.github.izakyl.folkways.core.travel.api.Hop;
import io.github.izakyl.folkways.core.travel.api.Passage;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

public final class FacilityRidePassage implements Passage {

    private static final ResourceLocation ID = ResourceLocation.parse("folkways_core:facility_ride");

    record RideFare(FacilityDefinition facility, int index, String from, String to) implements Fare {

        FacilityDefinition.Ride ride() {
            return facility.rides().get(index);
        }

        boolean available(ServerLevel level) {
            return facility.projectionReadings(level).get(FacilityDefinition.Ride.conditionKey(index))
                instanceof Value.Bool(boolean available) && available;
        }

        @Override
        public OptionalLong arriveBy(long when) {
            return OptionalLong.of(when + ride().ticks());
        }
    }

    static void project(FacilityDefinition facility, Map<String, Value> readings, List<Contribution> projected) {
        for (int index = 0; index < facility.rides().size(); index++) {
            if (!(readings.get(FacilityDefinition.Ride.conditionKey(index)) instanceof Value.Bool(boolean on) && on)) {
                continue;
            }
            Map<String, Stances.Cells> stops = new LinkedHashMap<>();
            facility.rides().get(index).stops().forEach((name, cells) -> stops.put(name,
                new Stances.Cells(cells.stream().map(facility.anchor()::at).collect(Collectors.toUnmodifiableSet()))));
            for (var from : stops.entrySet()) {
                for (var to : stops.entrySet()) {
                    if (!from.getKey().equals(to.getKey())) {
                        projected.add(new Contribution.Hop(new Hop(ID, from.getValue(), to.getValue(),
                            new RideFare(facility, index, from.getKey(), to.getKey()))));
                    }
                }
            }
        }
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public boolean through(Hop first, Hop next) {
        return false;
    }

    @Override
    public Conveyance aboard(Hop hop) {
        throw new IllegalArgumentException("a facility ride requires a walking conveyance");
    }

    @Override
    public Conveyance aboard(Hop hop, Locomotion moving, double speed, RefusalKind lost) {
        RideFare fare = (RideFare) hop.fare();
        Set<WorldPos> boarding = hop.boarding().cells();
        return new Conveyance() {
            private final Conveyance walking = moving.along(speed, lost);
            private DefinedFacilities service;
            private FacilityRides.Ticket ticket;
            private boolean approaching;
            private long deadline;

            @Override
            public void setOut(ServerLevel level, Mob body, Set<WorldPos> onward) {
                service = DefinedFacilities.forRide(level, fare.facility()).orElse(null);
                deadline = level.getGameTime() + FacilityRides.patience(fare);
                if (service != null && !RideStances.standingIn(body, hop.boarding())) {
                    approaching = true;
                    walking.setOut(level, body, boarding);
                }
            }

            @Override
            public Going step(ServerLevel level, Mob body, Set<WorldPos> onward) {
                if (service == null || !service.ownsRide(fare.facility(), level)
                        || ticket == null && (level.getGameTime() >= deadline || !fare.available(level))) {
                    return new Going.Failed(lost);
                }
                if (approaching) {
                    Going result = walking.step(level, body, boarding);
                    if (!(result instanceof Going.Arrived)) {
                        return result;
                    }
                    walking.release(level, body);
                    approaching = false;
                }
                if (ticket == null) {
                    if (!RideStances.standingIn(body, hop.boarding())) {
                        return new Going.Failed(lost);
                    }
                    ticket = service.queueRide(fare, hop, body, level);
                }
                return switch (ticket.state) {
                    case WAITING, RIDING -> Going.UNDERWAY;
                    case ARRIVED -> Going.ARRIVED;
                    case FAILED -> new Going.Failed(lost);
                };
            }

            @Override
            public Optional<Doing> doing() {
                return approaching ? walking.doing() : Optional.of(Doing.open(Doings.WAITING));
            }

            @Override
            public boolean awaitingArrival() {
                return ticket != null && ticket.state != FacilityRides.State.ARRIVED;
            }

            @Override
            public void release(ServerLevel level, Mob body) {
                if (approaching) {
                    walking.release(level, body);
                    approaching = false;
                }
                if (awaitingArrival()) {
                    ticket.state = FacilityRides.State.FAILED;
                }
            }
        };
    }
}
