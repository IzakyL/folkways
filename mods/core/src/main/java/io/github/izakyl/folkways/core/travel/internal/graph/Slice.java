package io.github.izakyl.folkways.core.travel.internal.graph;

import io.github.izakyl.folkways.core.travel.internal.PathfindingLoad;
import io.github.izakyl.folkways.core.travel.internal.TickBudget;
import net.minecraft.Util;

interface Slice {

    int SLICE_EXPANSIONS = 8_192;

    Slice UNTIL_ANSWERED = expansions -> false;

    boolean spent(int expansions);

    static Slice forWindow() {
        boolean counted = TickBudget.untimed();
        PathfindingLoad.sliceChosen(counted);
        if (counted) {
            return new Steps(SLICE_EXPANSIONS);
        }
        return new WallClock(TickBudget.remaining());
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
