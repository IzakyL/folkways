package io.github.izakyl.folkways.core.api.colony;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;

/**
 * Periodic work, spread over the server's ticks. Each cron says only how often it runs; the tick within that period
 * it runs on is picked here, so that as few crons as can be share any one tick. Crons of one colony are let go when
 * the colony closes, and all of them when the server stops.
 */
public final class Cadence {

    private static final List<Cron> CRONS = new ArrayList<>();

    static {
        ColonyLifecycle.listen(new ColonyLifecycle.Listener() {
            @Override
            public void closed(Colony colony, Closing why) {
                forget(colony);
            }
        });
    }

    private Cadence() {
    }

    public static final class Cron {

        private final Colony colony;
        private final int period;
        private final int phase;

        private Cron(Colony colony, int period, int phase) {
            this.colony = colony;
            this.period = period;
            this.phase = phase;
        }

        public int period() {
            return period;
        }

        public int phase() {
            return phase;
        }

        // Whether this tick is the one in its period the cron runs on, by the server's own tick count.
        public boolean due(MinecraftServer server) {
            return Math.floorMod(server.getTickCount(), period) == phase;
        }
    }

    public static Cron claim(Colony colony, int period) {
        return claim(colony, period, 1).getFirst();
    }

    // count crons of one period, each placed against every cron already running and the ones placed before it.
    public static synchronized List<Cron> claim(Colony colony, int period, int count) {
        if (period < 1 || count < 1) {
            throw new IllegalArgumentException("a cron needs a period and a count of at least one");
        }
        List<Cron> made = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Cron cron = new Cron(colony, period, phaseFor(period));
            CRONS.add(cron);
            made.add(cron);
        }
        return List.copyOf(made);
    }

    public static synchronized void forget(Colony colony) {
        CRONS.removeIf(cron -> cron.colony == colony);
    }

    public static synchronized void forgetServer() {
        CRONS.clear();
    }

    // Two crons meet once every lcm of their periods, and only if their phases agree modulo the gcd. The phase picked
    // meets the running crons least often; among equals, it is the one farthest from where any of them runs.
    private static int phaseFor(int period) {
        int best = 0;
        double fewest = Double.MAX_VALUE;
        int farthest = -1;
        for (int phase = 0; phase < period; phase++) {
            double meets = 0;
            int nearest = Integer.MAX_VALUE;
            for (Cron other : CRONS) {
                int shared = gcd(period, other.period);
                int apart = Math.floorMod(phase - other.phase, shared);
                if (apart == 0) {
                    meets += (double) shared / ((long) period * other.period);
                }
                nearest = Math.min(nearest, Math.min(apart, shared - apart));
            }
            if (meets < fewest || meets == fewest && nearest > farthest) {
                best = phase;
                fewest = meets;
                farthest = nearest;
            }
        }
        return best;
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
