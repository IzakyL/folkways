package io.github.izakyl.folkways.core.plan.api;

import io.github.izakyl.folkways.core.work.api.ReservationPool;

/** A submitted batch drawing on an already booked run. */
public interface ScopedWork extends Intent {

    ReservationPool reservations();
}
