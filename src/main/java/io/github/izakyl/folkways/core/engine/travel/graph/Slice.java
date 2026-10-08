package io.github.izakyl.folkways.core.engine.travel.graph;

import io.github.izakyl.folkways.core.engine.EngineMode;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import net.minecraft.Util;

public interface Slice {

    int SLICE_EXPANSIONS = 8_192;

    Slice UNTIL_ANSWERED = expansions -> false;

    boolean spent(int expansions);

    static Slice forWindow() {
        boolean counted = EngineMode.singleThread();
        PathfindingLoad.sliceChosen(counted);
        if (counted) {
            return new Steps(SLICE_EXPANSIONS);
        }
        return new WallClock(TravelBudget.wallClockNanos());
    }

    final class WallClock implements Slice {

        private final long until;

        WallClock(long nanos) {

            this.until = Util.getNanos() + nanos;
        }

        @Override
        public boolean spent(int expansions) {
            return Util.getNanos() >= this.until;
        }
    }

    final class Steps implements Slice {

        private int left;

        Steps(int expansions) {
            this.left = expansions;
        }

        @Override
        public boolean spent(int expansions) {
            this.left -= expansions;
            return this.left <= 0;
        }
    }
}
