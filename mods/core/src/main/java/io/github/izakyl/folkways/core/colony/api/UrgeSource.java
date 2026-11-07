package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.Worker;
import java.util.List;

@FunctionalInterface
public interface UrgeSource {
    List<Urge> urges(Colony colony, Worker who, ColonyView view);
}
