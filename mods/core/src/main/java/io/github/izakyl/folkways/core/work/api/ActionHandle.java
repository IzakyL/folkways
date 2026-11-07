package io.github.izakyl.folkways.core.work.api;

import io.github.izakyl.folkways.core.resident.api.Body;

// A private action owns its clock and body until released by its enclosing holding.
public interface ActionHandle extends WorkHolding {

    void reportEffort(Body body);
}
