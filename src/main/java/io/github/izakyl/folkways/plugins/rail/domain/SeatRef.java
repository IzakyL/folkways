package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.WorkSite;
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
