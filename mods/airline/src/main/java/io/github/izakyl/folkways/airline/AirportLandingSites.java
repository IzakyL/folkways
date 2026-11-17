package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.combat.api.LandingSites;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

final class AirportLandingSites {
    private AirportLandingSites() {
    }

    static List<LandingSites.Site> sites(ServerLevel level) {
        List<LandingSites.Site> result = new ArrayList<>();
        for (Airport airport : Airports.all(level)) {
            if (!airport.flaws().isEmpty()) {
                continue;
            }
            var address = new LandingSites.Airport(WorldSpaces.at(level, airport.tower()).toString(),
                airport.name(), TowerDuty.colony(level, airport.tower()).map(colony -> colony.id()));
            for (Helipad pad : airport.helipads()) {
                result.add(new LandingSites.Site(WorldSpaces.at(level, airport.tower()) + "/pad/" + WorldSpaces.at(level, pad.center()), airport.name(),
                    LandingSites.Kind.HELIPAD, WorldSpaces.at(level, pad.center().above()),
                    0, pad.size(), pad.size(), List.of(), address));
            }
            for (Airport.End end : airport.ends()) {
                result.add(new LandingSites.Site(WorldSpaces.at(level, airport.tower()) + "/runway/" + end.number(),
                    airport.name() + " " + end.number(), LandingSites.Kind.RUNWAY_END,
                    WorldSpaces.at(level, end.threshold().above()),
                    end.approachDirection(), end.runway().length(), end.runway().width(), List.of(), address));
            }
        }
        return List.copyOf(result);
    }
}
