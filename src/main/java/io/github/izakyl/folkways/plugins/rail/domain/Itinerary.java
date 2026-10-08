package io.github.izakyl.folkways.plugins.rail.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

// Each leg takes as long as it did the last time the train ran it, each stop as long as its schedule holds it. A
// leg never run, or a stop held for something other than time, ends what can be told. A looping line is followed
// twice around, so a train that has just left a station is still seen coming back to it.
public final class Itinerary {

    static final int LAPS = 2;

    public record Call(String station, OptionalInt ride, OptionalInt stay) {
    }

    private Itinerary() {
    }

    public static List<TransitNetwork.Stop> after(List<Call> calls, boolean cyclic, int next, int leavesIn) {
        List<TransitNetwork.Stop> stops = new ArrayList<>();
        int clock = leavesIn;
        for (int step = 0, call = next; step < LAPS * calls.size(); step++, call++) {
            if (call >= calls.size()) {
                if (!cyclic) {
                    break;
                }
                call = 0;
            }
            Call ahead = calls.get(call);
            if (ahead.ride().isEmpty()) {
                break;
            }
            clock += Math.max(1, ahead.ride().getAsInt());
            if (ahead.stay().isEmpty()) {
                stops.add(new TransitNetwork.Stop(ahead.station(), clock, clock));
                break;
            }
            stops.add(new TransitNetwork.Stop(ahead.station(), clock, clock + ahead.stay().getAsInt()));
            clock += ahead.stay().getAsInt();
        }
        return List.copyOf(stops);
    }
}
