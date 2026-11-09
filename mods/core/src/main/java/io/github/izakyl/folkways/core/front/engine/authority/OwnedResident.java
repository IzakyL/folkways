package io.github.izakyl.folkways.core.front.engine.authority;

import io.github.izakyl.folkways.core.resident.api.Body;

public final class OwnedResident {
    private final Body entity;

    OwnedResident(Body entity) {
        this.entity = entity;
    }

    public Body entity() {
        return entity;
    }
}
