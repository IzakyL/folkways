package io.github.izakyl.folkways.create.rail.domain;

import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Objects;
import java.util.UUID;

public record SeatRef(UUID vehicleId, int seatIndex) {

    public SeatRef {
        Objects.requireNonNull(vehicleId, "vehicleId");
        if (seatIndex < 0) {
            throw new IllegalArgumentException("seatIndex must be non-negative: " + seatIndex);
        }
    }

    public WorkSite.AtSeat site(WorldPos seenAt) {
        return new WorkSite.AtSeat(vehicleId, seatIndex, seenAt);
    }
}
