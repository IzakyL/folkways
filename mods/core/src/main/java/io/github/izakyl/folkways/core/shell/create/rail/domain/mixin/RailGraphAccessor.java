package io.github.izakyl.folkways.core.shell.create.rail.domain.mixin;

import com.simibubi.create.content.trains.graph.EdgePointStorage;
import com.simibubi.create.content.trains.graph.TrackGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(TrackGraph.class)
public interface RailGraphAccessor {

    @Accessor("edgePoints")
    EdgePointStorage folkways$edgePoints();
}
