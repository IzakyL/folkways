package io.github.izakyl.folkways.core.facility.api.control;

import java.util.Objects;
import java.util.UUID;

/** The named item control of the facility that owns a transfer. */
public record PartEndpoint(UUID facility, String control, ControlRef ref) {

    public PartEndpoint {
        Objects.requireNonNull(facility);
        Objects.requireNonNull(control);
        Objects.requireNonNull(ref);
    }
}
