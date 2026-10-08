package io.github.izakyl.folkways.core.engine.travel;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.resident.Searches;
import java.util.Arrays;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PathfindingLoad {

    private static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static long tick = Long.MIN_VALUE;
    private static int nodesThisTick;
    private static int searchesThisTick;

    private static long stepSlices;
    private static long wallSlices;

    private static long windowNodes;
    private static long windowSearches;
    private static int peakNodes;
    private static int peakSearches;

    private static long disproved;
    private static int peakPending;

    private static final Map<Object, int[]> held = new WeakHashMap<>();

    private static int found;
    private static int gaveUp;

    public enum Denial {

        FAR_END("farEnd"),

        NEAR_END("nearEnd"),

        FRONTIER("frontier"),

        NO_GOALS("noGoals");

        private final String label;

        Denial(String label) {
            this.label = label;
        }
    }

    private static final int[] denials = new int[Denial.values().length];

    private static final int SAMPLES_EACH = 2;

    private static int renumbered;

    private static int straightened;

    private static long straightenNanos;

    private static long freezeNanos;

    private static long publishMaxNanos;

    private static int rambles;

    private static int shortcuts;

    private static int hops;

    private static int laid;

    private static int closed;

    private static int far;

    private static int struck;

    private static int walked;

    private PathfindingLoad() {
    }

    public static void attachLaid() {
        laid++;
    }

    public static void attachClosed() {
        closed++;
    }

    public static void attachFar() {
        far++;
    }

    public static void edgeStruck() {
        struck++;
    }

    public static void edgeWalked() {
        walked++;
    }

    // Gaits count their own searches through the API; this is where those counts land.
    public static void install() {
        Searches.install(new Searches.Meter() {
            @Override
            public void starting(Level level) {
                searchStarting(level);
            }

            @Override
            public void expanded() {
                countExpansion();
            }
        });
    }

    public static void searchStarting(Level level) {
        currentTick(level);
        countSearch();
    }

    private static void currentTick(Level level) {
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return;
        }
        long now = level.getGameTime();
        if (now != tick) {
            foldTick();
            tick = now;
        }
    }

    public static void countExpansion() {
        nodesThisTick++;
    }

    private static void countSearch() {
        searchesThisTick++;
    }

    public static void disproved(int closures) {
        disproved += closures;
    }

    public static void asksPending(int pending) {
        peakPending = Math.max(peakPending, pending);
    }

    public static void graphHolds(Object graph, int waypoints, int edges, int exhausted,
                                  int pieces) {
        held.put(graph, new int[] {waypoints, edges, exhausted, pieces});
    }

    public static void questFound() {
        found++;
    }

    public static void questDenied(Denial why, long from, long goal, int goals) {
        int seen = ++denials[why.ordinal()];
        if (seen > SAMPLES_EACH || !FolkwaysConfig.logPlanHeartbeat()) {
            return;
        }
        LOGGER.info("pathfinding denied {} from={} goal={} goals={}",
            why.label, spell(from), goals > 0 ? spell(goal) : "-", goals);
    }

    public static void questGaveUp() {
        gaveUp++;
    }

    public static void renumbered() {
        renumbered++;
    }

    public static void straightened(int gone) {
        straightened += gone;
    }

    public static void published(long straighten, long freeze) {
        straightenNanos += straighten;
        freezeNanos += freeze;
        publishMaxNanos = Math.max(publishMaxNanos, straighten + freeze);
    }

    public static void rambled(int laid) {
        rambles++;
        shortcuts += laid;
    }

    public static void hopsWired(int count) {
        hops = Math.max(hops, count);
    }

    public static void sliceChosen(boolean counted) {
        if (counted) {
            stepSlices++;
        } else {
            wallSlices++;
        }
    }

    public static String drainHeartbeat() {
        int points = 0;
        int links = 0;
        int spent = 0;
        int pieces = 0;
        for (int[] one : held.values()) {
            points += one[0];
            links += one[1];
            spent += one[2];
            pieces += one[3];
        }
        String line = "searches=" + windowSearches + " nodes=" + windowNodes
            + " peakSearches/tick=" + peakSearches + " peakNodes/tick=" + peakNodes
            + " slices=" + stepSlices + "steps/" + wallSlices + "wall"
            + " graph=" + points + "pts/" + links + "edges/" + spent + "shut/" + pieces + "parts"
            + " disproved=" + disproved + "pts"
            + " asksPending=" + peakPending
            + " quests=" + found + "found/" + refused() + "denied/" + gaveUp + "gaveup"
            + " denied=" + byDenial()
            + " renumbered=" + renumbered
            + " straightened=" + straightened + "pts"
            + " publish=" + straightenNanos / 1000 + "usstraighten/"
            + freezeNanos / 1000 + "usfreeze/" + publishMaxNanos / 1000 + "usmax"
            + " rambles=" + rambles + "/" + shortcuts + "edges"
            + " hops=" + hops
            + " attached=" + laid + "laid/" + closed + "closed/" + far + "far"
            + " segments=" + struck + "struck/" + walked + "walked";
        found = 0;
        gaveUp = 0;
        Arrays.fill(denials, 0);
        renumbered = 0;
        straightened = 0;
        straightenNanos = 0;
        freezeNanos = 0;
        publishMaxNanos = 0;
        rambles = 0;
        shortcuts = 0;
        hops = 0;
        laid = 0;
        closed = 0;
        far = 0;
        struck = 0;
        walked = 0;
        disproved = 0;
        peakPending = 0;
        stepSlices = 0;
        wallSlices = 0;
        windowNodes = 0;
        windowSearches = 0;
        peakNodes = 0;
        peakSearches = 0;
        return line;
    }

    public static void forgetServer() {
        tick = Long.MIN_VALUE;
        nodesThisTick = 0;
        searchesThisTick = 0;
        windowNodes = 0;
        windowSearches = 0;
        peakNodes = 0;
        peakSearches = 0;
        disproved = 0;
        peakPending = 0;
        found = 0;
        gaveUp = 0;
        Arrays.fill(denials, 0);
        renumbered = 0;
        straightened = 0;
        straightenNanos = 0;
        freezeNanos = 0;
        publishMaxNanos = 0;
        rambles = 0;
        shortcuts = 0;
        hops = 0;
        held.clear();
    }

    private static String spell(long cell) {
        BlockPos at = BlockPos.of(cell);
        return at.getX() + "," + at.getY() + "," + at.getZ();
    }

    private static int refused() {
        int all = 0;
        for (int one : denials) {
            all += one;
        }
        return all;
    }

    private static String byDenial() {
        StringBuilder spelled = new StringBuilder();
        for (Denial why : Denial.values()) {
            if (!spelled.isEmpty()) {
                spelled.append('/');
            }
            spelled.append(denials[why.ordinal()]).append(why.label);
        }
        return spelled.toString();
    }

    private static void foldTick() {
        windowNodes += nodesThisTick;
        windowSearches += searchesThisTick;
        peakNodes = Math.max(peakNodes, nodesThisTick);
        peakSearches = Math.max(peakSearches, searchesThisTick);
        nodesThisTick = 0;
        searchesThisTick = 0;
    }
}
