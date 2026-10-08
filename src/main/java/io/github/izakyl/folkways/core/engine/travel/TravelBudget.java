package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.engine.EngineMode;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;

public final class TravelBudget {

    private static final long CLIMB_NANOS = 125_000L;

    private static final double BACK_OFF = 0.7D;

    private static long ceiling = -1L;
    private static long perGraph;
    private static boolean untimed;

    private static long windowCeiling;
    private static int windowTicks;
    private static int climbs;
    private static int backOffs;
    private static int lateTicks;
    private static int overTicks;
    private static long waysThisTick;

    private static long windowSpent;
    private static long worstSpent;
    private static long windowWork;
    private static long worstWork;

    private TravelBudget() {
    }

    private static long floorNanos() {
        return FolkwaysConfig.travelFloorMicros() * 1_000L;
    }

    private static long ceilingNanos() {
        return FolkwaysConfig.travelCeilingMicros() * 1_000L;
    }

    public static void beginTick(MinecraftServer server, int graphs) {
        untimed = EngineMode.singleThread();
        if (untimed) {
            return;
        }
        if (ceiling < 0) {
            ceiling = ceilingNanos();
        }
        long floor = floorNanos();
        ceiling = Math.clamp(ceiling, floor, ceilingNanos());
        perGraph = Math.max(floor, ceiling / Math.max(1, graphs));
        waysThisTick = 0;
        windowCeiling += ceiling;
        windowTicks++;
    }

    public static long wallClockNanos() {
        return perGraph > 0 ? perGraph : ceilingNanos();
    }

    public static void spent(long nanos) {
        waysThisTick += nanos;
    }

    public static void endTick(MinecraftServer server, long enteredAt, long work) {
        if (untimed || server == null) {
            return;
        }
        long spent = waysThisTick;
        windowSpent += spent;
        worstSpent = Math.max(worstSpent, spent);
        windowWork += work;
        worstWork = Math.max(worstWork, work);
        long deadline = server.getNextTickTime();
        if (enteredAt >= deadline) {

            lateTicks++;

            if (spent > ceiling) {
                overTicks++;
                backOffs++;
                ceiling = Math.max(floorNanos(), (long) (ceiling * BACK_OFF));
            }
            return;
        }
        if (Util.getNanos() > deadline) {
            overTicks++;

            if (spent >= perGraph) {
                backOffs++;
                ceiling = Math.max(floorNanos(), (long) (ceiling * BACK_OFF));
            }
            return;
        }
        if (ceiling < ceilingNanos()) {
            climbs++;
            ceiling = Math.min(ceilingNanos(), ceiling + CLIMB_NANOS);
        }
    }

    public static String drainHeartbeat() {
        if (windowTicks == 0) {
            return "budget=-";
        }
        String said = "budget=" + micros(windowCeiling / windowTicks)
            + "(floor" + FolkwaysConfig.travelFloorMicros() + "us/ceil" + FolkwaysConfig.travelCeilingMicros() + "us)"
            + " aimd=+" + climbs + "/-" + backOffs
            + " spent=" + micros(windowSpent / windowTicks) + "avg/" + micros(worstSpent) + "max"
            + " work=" + micros(windowWork / windowTicks) + "avg/" + micros(worstWork) + "max"
            + " late=" + lateTicks + " over=" + overTicks + "/" + windowTicks;
        windowCeiling = 0;
        windowTicks = 0;
        climbs = 0;
        backOffs = 0;
        lateTicks = 0;
        overTicks = 0;
        windowSpent = 0;
        worstSpent = 0;
        windowWork = 0;
        worstWork = 0;
        return said;
    }

    private static String micros(long nanos) {
        return (nanos / 1_000L) + "us";
    }

    public static void forgetServer() {
        ceiling = -1L;
        perGraph = 0;
        untimed = false;
        windowCeiling = 0;
        windowTicks = 0;
        climbs = 0;
        backOffs = 0;
        lateTicks = 0;
        overTicks = 0;
        windowSpent = 0;
        worstSpent = 0;
        windowWork = 0;
        worstWork = 0;
        waysThisTick = 0;
    }
}
