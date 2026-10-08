package io.github.izakyl.folkways.plugins.rail.domain.mixin;

import io.github.izakyl.folkways.core.api.FolkwaysMixinPlugin;
import java.util.List;
import java.util.Set;

public final class RailMixinPlugin extends FolkwaysMixinPlugin {

    @Override
    protected Set<String> createOnly() {
        return Set.of("AbstractContraptionEntityMixin", "TrainMapManagerMixin", "TrainMixin");
    }

    @Override
    protected List<Degradable> degradable() {
        return List.of(
            Degradable.common("AbstractContraptionEntityMixin",
                "com.simibubi.create.content.contraptions.AbstractContraptionEntity",
                "right-clicking a train's driver seat only sits the player down, so no train can be "
                    + "handed over to a colony and no resident is ever sent to drive one"),
            Degradable.common("TrainMixin",
                "com.simibubi.create.content.trains.entity.Train",
                "a train's departures go uncounted, so a resident waiting on a platform is never told the last "
                    + "run it could take has gone and waits there instead of finding another way"),
            Degradable.client("TrainMapManagerMixin",
                "com.simibubi.create.compat.trainmap.TrainMapManager",
                "the train map says nothing about who is driving a colony's train or how many of its "
                    + "seats are taken, leaving Create's own hover text as it was"));
    }
}
