package io.github.izakyl.folkways.core.shell.create.rail.domain.mixin;

import com.simibubi.create.content.trains.graph.EdgePointStorage;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import java.util.Map;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(EdgePointStorage.class)
public interface RailPointsAccessor {

    @Accessor("pointsByType")
    Map<EdgePointType<?>, Map<UUID, TrackEdgePoint>> folkways$pointsByType();
}
