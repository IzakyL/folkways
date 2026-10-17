package io.github.izakyl.folkways;

import java.util.List;
import java.util.function.Predicate;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FolkwaysConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final Predicate<Object> HASTE_STEP = value ->
        value instanceof Number step && step.doubleValue() > 0.0D && step.doubleValue() <= 16.0D;

    private static final ModConfigSpec.EnumValue<Mode> MODE;

    private static final ModConfigSpec.ConfigValue<List<? extends Number>> HAULING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> ASCETIC_APPETITE;

    private static final ModConfigSpec.IntValue HAUL_REACH_TICKS;
    private static final ModConfigSpec.IntValue PLAN_HORIZON_TICKS;
    private static final ModConfigSpec.IntValue PLAN_HORIZON_DEPTH;

    private static final ModConfigSpec.IntValue TRAVEL_FLOOR_MICROS;
    private static final ModConfigSpec.IntValue TRAVEL_CEILING_MICROS;

    private static final ModConfigSpec.IntValue SOLVE_THREADS;

    private static final ModConfigSpec.BooleanValue LOG_PLAN_HEARTBEAT;
    private static final ModConfigSpec.IntValue HEARTBEAT_CYCLES;

    public static final ModConfigSpec SPEC;

    static {

        BUILDER.push("planning");
        MODE = BUILDER
            .comment("How the engine shares out its work.",
                "DEFAULT solves plans on worker threads and gives working out where residents can walk a",
                "time budget per tick that grows and backs off with the server's load; a way nobody has",
                "worked out yet waits until it has been.",
                "SINGLE_THREAD does everything on the server thread and works a way out the moment it is",
                "asked, so every question gets a yes or a no and a run comes out the same twice. Ticks",
                "cost what they cost: meant for tests and for measuring, not for a busy server.")
            .defineEnum("mode", Mode.DEFAULT);
        SOLVE_THREADS = BUILDER
            .comment("How many threads plan in DEFAULT mode. Each colony weaves its plan on one and lays its",
                "routes on another, side by side, so two keep one colony's stages apart; more serve more",
                "colonies at once. 0 picks a quarter of the machine's cores, at least two and at most",
                "four; the server thread wants the rest. SINGLE_THREAD mode ignores it.")
            .defineInRange("solveThreads", 0, 0, 32);
        BUILDER.pop();

        BUILDER.push("perks");
        HAULING_HASTE = BUILDER
            .comment("The hauler's quick hands, as a multiplier on the wind-up of reaching into a chest,",
                "stooping for a dropped stack or handing goods to somebody. Hauling underpins every other",
                "trade, so this ladder is felt everywhere. One entry per rank, below 1 for faster.")
            .<Number>defineList("haulingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        ASCETIC_APPETITE = BUILDER
            .comment("What one rank of ascetic does to how fast a resident tires, as a multiplier on the",
                "vanilla exhaustion rate. Below 1 means a smaller appetite and fewer meals; above 1 means",
                "a hungrier resident. One entry per rank, and a rank past the end of the list counts as no",
                "change at all.")
            .<Number>defineList("asceticAppetite", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        BUILDER.pop();

        BUILDER.push("labor");
        HAUL_REACH_TICKS = BUILDER
            .comment("Wind-up, in ticks, for a hauler reaching into a container, handing a stack over or",
                "giving goods to a resident who asked for them. The hauler's quick hands scale it. This is",
                "the cost of the exchange itself; walking there is counted separately.")
            .defineInRange("haulReachTicks", 20, 0, 1_200);
        PLAN_HORIZON_TICKS = BUILDER
            .comment("How far ahead, in ticks, the schedule hands out work. Work that could start no sooner",
                "than this is left unassigned until a later plan, when whoever is free by then can take it:",
                "the colony plans again every few seconds, so a route laid further out only ties a resident",
                "to work they would wait on. Work that finishes something a resident has already begun is",
                "laid whatever its start. A resident with fewer than planHorizonDepth pieces of work on their",
                "route is handed work however far off it starts.")
            .defineInRange("planHorizonTicks", 3_000, 200, 1_000_000);
        PLAN_HORIZON_DEPTH = BUILDER
            .comment("How many pieces of work a resident's route holds before planHorizonTicks cuts it: work",
                "is left for a later plan only when it starts past the horizon and the route it would join is",
                "this deep already. A resident short of work - one walking far out to a works - is never left",
                "idle for the horizon's sake.")
            .defineInRange("planHorizonDepth", 8, 0, 10_000);
        BUILDER.pop();

        BUILDER.push("navigation");
        TRAVEL_CEILING_MICROS = BUILDER
            .comment("Microseconds per tick the whole server may spend working out where residents can",
                "walk, shared out between colonies and dimensions. The mod spends up to this and backs",
                "off whenever its own work pushes a tick past its deadline, climbing again while ticks",
                "fit — so this is the most it will ask for on a quiet server, not what it always takes.",
                "SINGLE_THREAD mode ignores it: there every question is worked out when it is asked.")
            .defineInRange("travelCeilingMicros", 8_000, 500, 40_000);
        TRAVEL_FLOOR_MICROS = BUILDER
            .comment("Microseconds per tick a single colony's reachability is never cut below. This is",
                "not a quality setting. Reachability is the only thing that turns \"no way known yet\"",
                "into an answer; a colony that never finishes working one out has jobs nobody can be",
                "given, and the unanswered questions pile up faster than the saved time pays for. The",
                "floor is what stops backing off from feeding itself.")
            .defineInRange("travelFloorMicros", 250, 50, 2_000);
        BUILDER.pop();

        BUILDER.push("diagnostics");
        LOG_PLAN_HEARTBEAT = BUILDER
            .comment("Write the per-colony planning and pathfinding heartbeat to the server log. One line",
                "per colony per heartbeat, plus two server-wide lines. Turn it off on a busy server that",
                "does not need the readout; the counters are drained either way, so turning it back on",
                "reports the window since then rather than everything since start.")
            .define("logPlanHeartbeat", true);
        HEARTBEAT_CYCLES = BUILDER
            .comment("Heartbeats come once every this many twenty-tick cycles of simulation.")
            .defineInRange("heartbeatCycles", 10, 1, 600);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private FolkwaysConfig() {
    }

    public enum Mode {
        DEFAULT,
        SINGLE_THREAD
    }

    public static Mode mode() {
        return read(MODE);
    }

    public static int solveThreads() {
        int asked = read(SOLVE_THREADS);
        return asked > 0 ? asked
            : Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 2, 4);
    }

    public static List<? extends Number> haulingHaste() {
        return read(HAULING_HASTE);
    }

    public static List<? extends Number> asceticAppetite() {
        return read(ASCETIC_APPETITE);
    }

    public static int haulReachTicks() {
        return read(HAUL_REACH_TICKS);
    }

    public static int planHorizonDepth() {
        return read(PLAN_HORIZON_DEPTH);
    }

    public static int planHorizonTicks() {
        return read(PLAN_HORIZON_TICKS);
    }

    public static long travelCeilingMicros() {
        return read(TRAVEL_CEILING_MICROS);
    }

    public static long travelFloorMicros() {
        return read(TRAVEL_FLOOR_MICROS);
    }

    public static boolean logPlanHeartbeat() {
        return read(LOG_PLAN_HEARTBEAT);
    }

    public static int heartbeatCycles() {
        return read(HEARTBEAT_CYCLES);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
