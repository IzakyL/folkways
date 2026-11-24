package io.github.izakyl.folkways.vanilla.create.rail.domain;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.read.Railways;
import io.github.izakyl.folkways.core.shell.create.rail.domain.CreateRailGraph;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

final class CreateRailways implements Railways.Source {

    static boolean available() {
        return CreateRailGraph.available();
    }

    @Override
    public List<Railways.Station> stations(MinecraftServer server, Colony colony) {
        var presence = colony.service(RailContent.ID, RailPresence.class);
        return CreateRailGraph.stations(server, train -> presence.isPresent() && presence.get().holds(train));
    }

    @Override
    public Railways.Tracks tracks(ServerLevel level, Colony colony, AABB bounds) {
        return CreateRailGraph.tracks(level, bounds);
    }
}
