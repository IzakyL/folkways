package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.core.api.passage.Hop;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.plugins.rail.domain.TransitSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public final class RailLegs {

    // run is the departure count of the run this ride waits for, when the plan caught one.
    record Leg(UUID train, String board, String alight, Stances.Cells boarding,
               Stances.Cells landing, Timetable runs, OptionalLong run) {

        Leg(UUID train, String board, String alight, Stances.Cells boarding, Stances.Cells landing, Timetable runs) {
            this(train, board, alight, boarding, landing, runs, OptionalLong.empty());
        }

        Hop hop() {
            return new Hop(serviceOf(train), boarding, landing,
                new RailFare(train, runs, board, alight));
        }
    }

    private record Ridden(UUID train, String board, String alight) {
    }

    private RailLegs() {
    }

    static ResourceLocation serviceOf(UUID train) {
        return ResourceLocation.fromNamespaceAndPath(RailPassage.ID.getNamespace(), "train/" + train);
    }

    static List<Leg> of(TransitSnapshot taken) {
        List<Leg> legs = new ArrayList<>();
        Set<Ridden> seen = new LinkedHashSet<>();
        for (TransitSnapshot.Line line : taken.lines()) {
            if (!boardable(line)) {
                continue;
            }
            Timetable runs = runsOf(taken.capturedAt(), line);
            for (int call = 0; call + 1 < runs.calls().size(); call++) {
                String board = runs.calls().get(call);
                String alight = runs.calls().get(call + 1);
                if (board.equals(alight) || !seen.add(new Ridden(line.train(), board, alight))) {
                    continue;
                }
                Stances.Cells boarding = line.footings().get(board);
                Stances.Cells landing = line.footings().get(alight);
                if (boarding == null || landing == null) {
                    continue;
                }
                legs.add(new Leg(line.train(), board, alight, boarding, landing, runs));
            }
        }
        return List.copyOf(legs);
    }

    private static boolean boardable(TransitSnapshot.Line line) {
        // A full train still runs: a rider waits on the platform for a seat, rather than the line vanishing from the
        // network each time the last one is taken.
        return line.conducted() && line.stations().size() >= 2;
    }

    static Timetable runsOf(long capturedAt, TransitSnapshot.Line line) {
        List<TransitNetwork.Stop> stops = line.timetable();
        List<String> calls = new ArrayList<>(stops.size());
        long[] arrive = new long[stops.size()];
        long[] leave = new long[stops.size()];
        long[] departs = new long[stops.size()];
        for (int k = 0; k < stops.size(); k++) {
            calls.add(stops.get(k).station());
            arrive[k] = capturedAt + stops.get(k).arrivesIn();
            leave[k] = capturedAt + stops.get(k).leavesIn();
            departs[k] = line.firstDeparture() + k;
        }
        return new Timetable(calls, arrive, leave, departs);
    }
}
