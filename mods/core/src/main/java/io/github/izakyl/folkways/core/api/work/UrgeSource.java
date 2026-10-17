package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.ColonyView;
import java.util.List;

@FunctionalInterface
public interface UrgeSource {
    List<Urge> urges(Colony colony, Worker who, ColonyView view);
}
