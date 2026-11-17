package io.github.izakyl.folkways.airline;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

public final class Airports {
    public static final int RESCAN_TICKS = 1200;
    public static final int TICK_BUDGET = 8192;
    public static final long TICK_NANOS = 2_000_000;
    private static final Map<MinecraftServer, Scanning> SCANNERS = new HashMap<>();

    private Airports() {
    }

    public static Optional<Airport> byName(ServerLevel level, String name) {
        if (name.isBlank()) {
            return Optional.empty();
        }
        AirportRegistry registry = AirportRegistry.of(level);
        List<BlockPos> matches = registry.named(name);
        List<Airport> airports = matches.stream().map(pos -> registry.airport(level, pos)).distinct().toList();
        return airports.size() == 1 && airports.getFirst().flaws().stream()
            .noneMatch(problem -> problem.kind() == AirportFlaw.NAME_MISMATCH)
            ? Optional.of(airports.getFirst()) : Optional.empty();
    }

    public static List<Airport> all(ServerLevel level) {
        AirportRegistry registry = AirportRegistry.of(level);
        return registry.towers().stream().map(pos -> registry.airport(level, pos)).distinct().toList();
    }

    public static Optional<Airport> at(ServerLevel level, BlockPos tower) {
        AirportRegistry registry = AirportRegistry.of(level);
        return registry.contains(tower) ? Optional.of(registry.airport(level, tower)) : Optional.empty();
    }

    public static void register(ServerLevel level, BlockPos tower) {
        if (validTower(level, tower)) {
            AirportRegistry.of(level).register(tower);
        }
    }

    public static void rename(ServerLevel level, BlockPos tower, String name) {
        if (validTower(level, tower)) {
            AirportRegistry.of(level).rename(level, tower, name);
        }
    }

    public static void remove(ServerLevel level, BlockPos tower) {
        AirportRegistry.of(level).remove(tower);
    }

    public static void request(ServerLevel level, BlockPos tower) {
        if (!validTower(level, tower)) {
            return;
        }
        register(level, tower);
        SCANNERS.computeIfAbsent(level.getServer(), ignored -> new Scanning()).request(level, tower);
    }

    // Package-local lifecycle seam: fixtures can cancel only their own outstanding survey.
    static void cancelSurvey(ServerLevel level, BlockPos tower) {
        Scanning scanner = SCANNERS.get(level.getServer());
        if (scanner != null) {
            Address address = new Address(level, tower);
            scanner.queue.removeIf(address::equals);
            scanner.pending.remove(address);
            if (scanner.active != null && scanner.active.level() == level && scanner.active.tower().equals(tower)) {
                scanner.active = null;
            }
        }
    }

    public static boolean scanning(ServerLevel level, BlockPos tower) {
        Scanning scanner = SCANNERS.get(level.getServer());
        return scanner != null && scanner.pending.contains(new Address(level, tower));
    }

    static boolean available(ServerLevel level, BlockPos tower) {
        return AirportRegistry.of(level).contains(tower) && (!level.hasChunkAt(tower) || validTower(level, tower));
    }

    static boolean validTower(ServerLevel level, BlockPos tower) {
        return level.hasChunkAt(tower) && level.getBlockState(tower).is(AirlineContent.AIRPORT_TOWER.get());
    }

    static void tick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % RESCAN_TICKS == 0) {
            requestRescan(server);
        }
        Scanning scanner = SCANNERS.get(server);
        if (scanner != null) {
            scanner.tick(server);
        }
    }

    static void requestRescan(MinecraftServer server) {
        SCANNERS.computeIfAbsent(server, ignored -> new Scanning()).rescanRequested = true;
    }

    // Independent copy counters are sampled at the work itself, rather than inferred from scheduler iterations.
    record TickWork(int tick, int steps, int copySteps, int copiedCells, int rescanTowers, long nanos) {
    }

    static TickWork lastTickWork(MinecraftServer server) {
        Scanning scanner = SCANNERS.get(server);
        return scanner == null ? new TickWork(server.getTickCount(), 0, 0, 0, 0, 0) : scanner.lastWork;
    }

    static void stopped(ServerStoppedEvent event) {
        AirportRegistry.stopped(event);
        Scanning scanner = SCANNERS.remove(event.getServer());
        if (scanner != null) {
            scanner.worker.shutdownNow();
        }
    }

    private record Address(ServerLevel level, BlockPos pos) {
    }

    private static final class Scanning {
        private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "folkways-airport-survey");
            thread.setDaemon(true);
            return thread;
        });
        private final ArrayDeque<Address> queue = new ArrayDeque<>();
        private final Set<Address> pending = new HashSet<>();
        private AirportScan active;
        private boolean rescanRequested;
        private Iterator<ServerLevel> rescanLevels;
        private ServerLevel rescanLevel;
        private BlockPos rescanAfter;
        private boolean rescanTurn = true;
        private TickWork lastWork = new TickWork(-1, 0, 0, 0, 0, 0);
        private int copySteps;
        private int copiedCells;
        private int rescanTowers;

        private void request(ServerLevel level, BlockPos tower) {
            Address address = new Address(level, tower.immutable());
            if (pending.add(address)) {
                queue.addLast(address);
            }
        }

        private void tick(MinecraftServer server) {
            long started = System.nanoTime();
            long deadline = started + TICK_NANOS;
            copySteps = 0;
            copiedCells = 0;
            rescanTowers = 0;
            int steps = 0;
            while (steps < TICK_BUDGET && System.nanoTime() < deadline) {
                boolean scanReady = active == null ? !queue.isEmpty() : !active.waiting();
                boolean rescanReady = rescanRequested || rescanLevels != null;
                if (rescanReady && (rescanTurn || !scanReady)) {
                    rescanStep(server);
                    rescanTurn = false;
                } else if (scanReady) {
                    scanStep();
                    rescanTurn = true;
                } else {
                    break;
                }
                steps++;
            }
            lastWork = new TickWork(server.getTickCount(), steps, copySteps, copiedCells, rescanTowers, System.nanoTime() - started);
        }

        private void rescanStep(MinecraftServer server) {
            if (rescanLevels == null) {
                rescanRequested = false;
                rescanLevels = server.getAllLevels().iterator();
            }
            if (rescanLevel == null) {
                if (rescanLevels.hasNext()) {
                    rescanLevel = rescanLevels.next();
                    rescanAfter = null;
                } else {
                    rescanLevels = null;
                }
                return;
            }
            AirportRegistry registry = AirportRegistry.of(rescanLevel);
            BlockPos tower = registry.nextTower(rescanAfter);
            if (tower == null) {
                rescanLevel = null;
                return;
            }
            rescanAfter = tower;
            rescanTowers++;
            if (validTower(rescanLevel, tower)) {
                request(rescanLevel, tower);
            } else if (rescanLevel.hasChunkAt(tower)) {
                registry.remove(tower);
            }
        }

        private void scanStep() {
            if (active == null) {
                Address address = queue.removeFirst();
                if (!validTower(address.level(), address.pos())) {
                    pending.remove(address);
                    return;
                }
                active = new AirportScan(address.level(), address.pos());
                return;
            }
            int beforeSteps = active.copySteps();
            int beforeCells = active.copiedCells();
            boolean finished = active.advance(worker);
            copySteps += active.copySteps() - beforeSteps;
            copiedCells += active.copiedCells() - beforeCells;
            if (finished) {
                if (validTower(active.level(), active.tower())) {
                    if (active.unchanged()) {
                        AirportRegistry.of(active.level()).cache(active.result());
                        AirportRegistry.of(active.level()).complete(active);
                    } else {
                        queue.addLast(new Address(active.level(), active.tower()));
                        active = null;
                        return;
                    }
                }
                pending.remove(new Address(active.level(), active.tower()));
                active = null;
            }
        }
    }
}
