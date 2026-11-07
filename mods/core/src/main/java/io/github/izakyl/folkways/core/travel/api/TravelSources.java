package io.github.izakyl.folkways.core.travel.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

public interface TravelSources {

    UUID colonyId();

    Iterable<Body> bodiesIn(ServerLevel level);

    List<Hop> hops();
}
