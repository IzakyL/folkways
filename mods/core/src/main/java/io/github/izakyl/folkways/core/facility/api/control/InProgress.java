package io.github.izakyl.folkways.core.facility.api.control;

import java.util.List;

/**
 * A server-thread snapshot of resources accepted into a machine's internal batch and its progress towards output
 * (0 to 1). The adapter supplies detached resource values in the same units as the associated port.
 */
public record InProgress<R>(List<R> contents, double progress) {

    public InProgress {
        contents = List.copyOf(contents);
        if (!(progress >= 0 && progress <= 1)) {
            throw new IllegalArgumentException("progress outside [0, 1]");
        }
    }
}
