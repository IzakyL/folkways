package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.engine.EngineMode;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.colony.ColonyRegistry;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.core.engine.travel.PathfindingLoad;
import io.github.izakyl.folkways.core.engine.travel.TravelBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
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

    // The one Labor the mod runs, so the live fuzzers (tests/fuzz) can read a colony's labor as its readout does.
    private static volatile Labor running;

    private Solves solves;

    private boolean offThread;

    private int graphs;

    private int simulationTicks;

    public Labor() {
        running = this;
    }

    static Optional<ColonyLabor> site(UUID colony, ResourceKey<Level> dimension) {
        Labor labor = running;
        return labor == null ? Optional.empty() : Optional.ofNullable(labor.sites.get(new Site(colony, dimension)));
    }

    public void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!server.tickRateManager().runsNormally()) {
            return;
        }
        simulationTicks++;
        if (!(server instanceof GameTestServer)) {
            work(server);
        }
        pulse(server);
    }

    private void pulse(MinecraftServer server) {
        for (ColonyData colony : List.copyOf(ColonyRegistry.get(server).colonies())) {
            colony.works().pulse(server);
        }
    }

    private void work(MinecraftServer server) {
        ColonyRegistry registry = ColonyRegistry.get(server);
        boolean heartbeat = simulationTicks % (BEAT_TICKS * FolkwaysConfig.heartbeatCycles()) == 0;
        long enteredAt = Util.getNanos();
        TravelBudget.beginTick(server, graphs);
        int asking = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (ColonyData colony : registry.colonies()) {
                Site key = new Site(colony.colonyId(), level.dimension());
                boolean deserted = colony.holdings().bodiesIn(level).isEmpty();
                if (deserted && !sites.containsKey(key)) {
                    continue;
                }
                if (ColonyWays.advance(colony, level)) {
                    asking++;
                }
                ColonyLabor labor = sites.compute(key, (site, previous) -> previous == null
                    ? new ColonyLabor(colony, level.dimension()) : previous.current());
                labor.run(level, planFor(colony.colonyId()));
                if (labor.desertedLongEnough(deserted, level.getGameTime())) {
                    sites.remove(key).close();
                    ColonyWays.forget(colony.colonyId(), level.dimension());
                }
            }
        }
        graphs = asking;
        TravelBudget.endTick(server, enteredAt, Util.getNanos() - enteredAt);
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
        if (heartbeat) {
            beat(server, registry);
        }
    }

    private void beat(MinecraftServer server, ColonyRegistry registry) {
        for (ColonyData colony : registry.colonies()) {
            colony.reconcile(server);
        }
        warnOnStuckSolves();
        String nav = PathfindingLoad.drainHeartbeat() + " " + TravelBudget.drainHeartbeat();
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
        offThread = EngineMode.begin() == FolkwaysConfig.Mode.DEFAULT;
        if (!offThread) {
            LOGGER.info("single-thread mode: solves and ways are worked out on the tick");
            return;
        }
        int threads = FolkwaysConfig.solveThreads();
        solves = new Solves(threads);
        LOGGER.info("solves run on {} thread(s), one colony at a time each", threads);
    }

    private Executor planFor(UUID colony) {
        return offThread && solves != null ? solves.of(colony) : Runnable::run;
    }

    public void shutdown() {
        sites.values().forEach(ColonyLabor::close);
        sites.clear();
        simulationTicks = 0;
        graphs = 0;
        offThread = false;
        if (solves != null) {
            solves.shutdown();
            solves = null;
        }
    }
}
