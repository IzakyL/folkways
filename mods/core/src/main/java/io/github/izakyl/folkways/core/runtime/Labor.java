package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysConfig;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.colony.internal.ColonyMemberWrites;
import io.github.izakyl.folkways.core.colony.internal.ColonyRegistry;
import io.github.izakyl.folkways.core.travel.internal.ColonyWays;
import io.github.izakyl.folkways.core.travel.internal.PathfindingLoad;
import io.github.izakyl.folkways.core.travel.internal.TickBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.Util;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Labor {

    static final Logger LOGGER = LoggerFactory.getLogger("folkways-labor");

    private static final long SLOW_SOLVE_NANOS = 10L * 1_000_000_000L;

    private static final int BEAT_TICKS = 20;

    private record Site(UUID colony, ResourceKey<Level> dimension) {
    }

    private final Map<Site, ColonyLabor> sites = new LinkedHashMap<>();

    // The one Labor the mod runs, so a colony's census can read its labor as its readout does.
    private static volatile Labor running;

    private Solves solves;

    private boolean offThread;

    private int siteCursor;

    private int simulationTicks;

    public Labor() {
        running = this;
    }

    static Optional<ColonyLabor> site(UUID colony, ResourceKey<Level> dimension) {
        Labor labor = running;
        return labor == null ? Optional.empty() : Optional.ofNullable(labor.sites.get(new Site(colony, dimension)));
    }

    public static List<io.github.izakyl.folkways.core.work.api.ReservedInput> reservedInputs(UUID colony,
            ResourceKey<Level> dimension) {
        return site(colony, dimension).map(ColonyLabor::reservedInputs).orElse(List.of());
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!server.tickRateManager().runsNormally()) {
            return;
        }
        long enteredAt = Util.getNanos();
        TickBudget.beginTick(server, enteredAt);
        simulationTicks++;
        ColonyMemberWrites.reconcile(server);
        if (!(server instanceof GameTestServer)) {
            work(server);
        }
        pulse(server);
        TickBudget.endTick(Util.getNanos() - enteredAt);
        if (!(server instanceof GameTestServer)
            && simulationTicks % (BEAT_TICKS * FolkwaysConfig.heartbeatCycles()) == 0) {
            beat(server, ColonyRegistry.get(server));
        }
    }

    private void pulse(MinecraftServer server) {
        for (ColonyData colony : List.copyOf(ColonyRegistry.get(server).colonies())) {
            colony.works().pulse(server);
        }
    }

    private void work(MinecraftServer server) {
        ColonyRegistry registry = ColonyRegistry.get(server);
        record Visit(ColonyData colony, ServerLevel level, Site key, boolean deserted) {
        }
        List<Visit> visits = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            for (ColonyData colony : registry.colonies()) {
                Site key = new Site(colony.colonyId(), level.dimension());
                boolean deserted = colony.claims().bodiesIn(level).isEmpty();
                if (!deserted || sites.containsKey(key)) {
                    visits.add(new Visit(colony, level, key, deserted));
                }
            }
        }
        int count = visits.size();
        int start = EngineMode.singleThread() ? 0 : startSite(siteCursor, count);
        for (int i = 0; i < count; i++) {
            Visit visit = visits.get((start + i) % count);
            TickBudget.openSite(count - i);
            try {
                ColonyWays.advance(visit.colony(), visit.level());
                ColonyLabor labor = sites.compute(visit.key(), (site, previous) -> previous == null
                    ? new ColonyLabor(visit.colony(), visit.level().dimension()) : previous.current());
                labor.run(visit.level(), planFor(visit.colony().colonyId()));
                if (labor.desertedLongEnough(visit.deserted(), visit.level().getGameTime())) {
                    sites.remove(visit.key()).close();
                    ColonyWays.forget(visit.colony().colonyId(), visit.level().dimension());
                }
            } finally {
                TickBudget.closeSite();
            }
        }
        siteCursor = count == 0 ? 0 : (start + 1) % count;
        sites.keySet().removeIf(site -> {
            if (registry.find(site.colony()).isPresent()) {
                return false;
            }
            sites.get(site).close();
            if (solves != null) {
                solves.forget(site.colony());
            }
            return true;
        });
    }

    static int startSite(int cursor, int count) {
        return count == 0 ? 0 : Math.floorMod(cursor, count);
    }

    private void beat(MinecraftServer server, ColonyRegistry registry) {
        for (ColonyData colony : registry.colonies()) {
            colony.reconcile(server);
        }
        warnOnStuckSolves();
        String nav = PathfindingLoad.drainHeartbeat() + " " + TickBudget.drainHeartbeat();
        if (!FolkwaysConfig.logPlanHeartbeat()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<Site, ColonyLabor> site : sites.entrySet()) {
            lines.add(site.getKey().colony() + "@" + site.getKey().dimension().location() + " "
                + site.getValue().heartbeat());
            for (String stall : site.getValue().stalls()) {
                lines.add("  " + stall);
            }
        }
        LOGGER.info("labor heartbeat colonies={} {}", registry.colonies().size(), nav);
        lines.forEach(line -> LOGGER.info("  {}", line));
    }

    private void warnOnStuckSolves() {
        long now = Util.getNanos();
        for (Map.Entry<Site, ColonyLabor> site : sites.entrySet()) {
            long nanos = site.getValue().solvingFor(now);
            if (nanos >= SLOW_SOLVE_NANOS) {
                LOGGER.warn("{}@{} has been solving for {}s — that colony is not planning, and the"
                    + " numbers below it are the last reading a finished cycle froze",
                    site.getKey().colony(), site.getKey().dimension().location(),
                    nanos / 1_000_000_000L);
            }
        }
    }

    public void begin(MinecraftServer server) {
        shutdown();
        TickBudget.startServer(server);
        offThread = EngineMode.begin() == FolkwaysConfig.Mode.DEFAULT;
        if (!offThread) {
            LOGGER.info("single-thread mode: solves and ways are worked out on the tick");
            return;
        }
        int threads = FolkwaysConfig.solveThreads();
        solves = new Solves(threads);
        LOGGER.info("plans run on {} thread(s), each colony weaving and scheduling apart", threads);
    }

    private Lanes planFor(UUID colony) {
        return offThread && solves != null ? solves.of(colony) : Lanes.INLINE;
    }

    public void shutdown() {
        ColonyMemberWrites.forgetServer();
        sites.values().forEach(ColonyLabor::close);
        sites.clear();
        simulationTicks = 0;
        siteCursor = 0;
        offThread = false;
        if (solves != null) {
            solves.shutdown();
            solves = null;
        }
    }
}
