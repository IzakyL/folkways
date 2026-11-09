package io.github.izakyl.folkways.core.travel.internal;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;

public final class TickBudget {

    private static final double SHARE = 0.5D;

    private static long remaining;
    private static long siteRemaining;
    private static boolean untimed;
    private static boolean gameTests;
    private static boolean singleThread;

    private static long slackSum;
    private static long grantedSum;
    private static int ticks;
    private static int skipped;
    private static long spentThisTick;
    private static long windowSpent;
    private static long worstSpent;
    private static long windowWork;
    private static long worstWork;

    private TickBudget() {
    }

    public static void startServer(MinecraftServer server) {
        gameTests = server instanceof GameTestServer;
        untimed = untimed();
    }

    public static void singleThread(boolean enabled) {
        singleThread = enabled;
    }

    public static boolean untimed() {
        return gameTests || singleThread;
    }

    public static void beginTick(MinecraftServer server, long now) {
        untimed = untimed();
        if (!untimed) {
            beginTick(server.getNextTickTime(), now, FolkwaysConfig.travelCeilingMicros() * 1_000L);
        }
    }

    static void beginTick(long deadline, long now, long ceiling) {
        untimed = false;
        long slack = deadline - now;
        remaining = slack <= 0 ? 0 : Math.min(ceiling, (long) (SHARE * slack));
        siteRemaining = remaining;
        spentThisTick = 0;
        slackSum += slack;
        grantedSum += remaining;
        ticks++;
        if (slack <= 0) {
            skipped++;
        }
    }

    public static long remaining() {
        return Math.min(remaining, siteRemaining);
    }

    public static void openSite(int sitesLeft) {
        siteRemaining = remaining / sitesLeft;
    }

    public static void closeSite() {
        siteRemaining = remaining;
    }

    public static void spent(long nanos) {
        if (untimed) {
            return;
        }
        long elapsed = Math.max(0, nanos);
        spentThisTick += elapsed;
        remaining = Math.max(0, remaining - elapsed);
        siteRemaining = Math.max(0, siteRemaining - elapsed);
    }

    public static void endTick(long work) {
        if (untimed) {
            return;
        }
        windowSpent += spentThisTick;
        worstSpent = Math.max(worstSpent, spentThisTick);
        windowWork += work;
        worstWork = Math.max(worstWork, work);
        remaining = 0;
        siteRemaining = 0;
    }

    public static String drainHeartbeat() {
        if (ticks == 0) {
            return "budget=-";
        }
        String said = "slack=" + micros(slackSum / ticks) + "avg"
            + " budget=" + micros(grantedSum / ticks) + "avg(ceil" + FolkwaysConfig.travelCeilingMicros() + "us)"
            + " spent=" + micros(windowSpent / ticks) + "avg/" + micros(worstSpent) + "max"
            + " work=" + micros(windowWork / ticks) + "avg/" + micros(worstWork) + "max"
            + " skipped=" + skipped + "/" + ticks;
        slackSum = 0;
        grantedSum = 0;
        ticks = 0;
        skipped = 0;
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
        remaining = 0;
        siteRemaining = 0;
        untimed = false;
        gameTests = false;
        slackSum = 0;
        grantedSum = 0;
        ticks = 0;
        skipped = 0;
        spentThisTick = 0;
        windowSpent = 0;
        worstSpent = 0;
        windowWork = 0;
        worstWork = 0;
    }
}
