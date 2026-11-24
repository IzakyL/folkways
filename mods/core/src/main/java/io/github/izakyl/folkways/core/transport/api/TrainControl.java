package io.github.izakyl.folkways.core.transport.api;

import java.util.UUID;

/** A control available to crew aboard a train. */
public interface TrainControl {

    boolean belongsToTrain(UUID train);
}
