package io.github.izakyl.folkways;

import java.util.List;
import java.util.function.Predicate;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class FolkwaysConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final Predicate<Object> HASTE_STEP = value ->
        value instanceof Number step && step.doubleValue() > 0.0D && step.doubleValue() <= 16.0D;

    private static final ModConfigSpec.EnumValue<Mode> MODE;

    private static final ModConfigSpec.BooleanValue IMMIGRATION_ENABLED;
    private static final ModConfigSpec.IntValue FOOD_PER_RESIDENT;
    private static final ModConfigSpec.IntValue SETTLER_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue MAX_WAITING_SETTLERS;

    private static final ModConfigSpec.IntValue MAX_FISH_CELLS;
    private static final ModConfigSpec.IntValue MAX_PASTURE_CELLS;
    private static final ModConfigSpec.IntValue MAX_FARM_CELLS;
    private static final ModConfigSpec.IntValue MAX_PATROL_POINTS;
    private static final ModConfigSpec.IntValue PATROL_WARD_RADIUS;
    private static final ModConfigSpec.IntValue PATROL_WARD_TICKS;
    private static final ModConfigSpec.IntValue MAX_BUILD_CELLS;
    private static final ModConfigSpec.LongValue MAX_BLUEPRINT_VOLUME;

    private static final ModConfigSpec.ConfigValue<List<? extends Number>> BUILDING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> CRAFTING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> CONDUCTING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> DISPATCH_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> HERDING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> FARMING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> FISHING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> HAULING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> ASCETIC_APPETITE;

    private static final ModConfigSpec.IntValue HAUL_REACH_TICKS;
    private static final ModConfigSpec.IntValue PLAN_HORIZON_TICKS;
    private static final ModConfigSpec.IntValue DISPATCH_REACH_TICKS;
    private static final ModConfigSpec.IntValue SEAT_TICKS;

    private static final ModConfigSpec.DoubleValue PATH_SEARCH_BUDGET;
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
            .comment("How many colonies may solve at once in DEFAULT mode. A colony's own dimensions still",
                "solve one at a time — they share the colony's places — so this is a cap on colonies, not",
                "on threads doing useful work. 0 picks a quarter of the machine's cores, at least one and",
                "at most four; the server thread wants the rest. SINGLE_THREAD mode ignores it.")
            .defineInRange("solveThreads", 0, 0, 32);
        BUILDER.pop();

        BUILDER.push("immigration");
        IMMIGRATION_ENABLED = BUILDER
            .comment("Whether settlers gather outside colonies at all. Off leaves every other system",
                "untouched; residents can still be placed by other means.")
            .define("enabled", true);
        FOOD_PER_RESIDENT = BUILDER
            .comment("One resident's daily ration. A colony admits a newcomer only while its stored food",
                "covers (population + 1) times this.")
            .defineInRange("foodPerResident", 8, 1, 256);
        SETTLER_INTERVAL_TICKS = BUILDER
            .comment("Day-time ticks one settler takes to gather at a colony of nobody. A colony of N",
                "takes (N + 1) times this, so a small colony fills fast and a large one slowly.")
            .defineInRange("settlerIntervalTicks", 2_400, 20, 1_728_000);
        MAX_WAITING_SETTLERS = BUILDER
            .comment("How many settlers may stand waiting at one colony. Nothing gathers past it, so a",
                "world left running does not hand back a crowd.")
            .defineInRange("maxWaitingSettlers", 5, 1, 64);
        BUILDER.pop();

        BUILDER.push("zones");
        MAX_FISH_CELLS = BUILDER
            .comment("Largest fishing zone a player may mark out, in cells. The box marks where a fisher",
                "stands, so this bounds the scan for a standable cell with water in reach of it.")
            .defineInRange("maxFishCells", 4_096, 1, 262_144);
        MAX_PASTURE_CELLS = BUILDER
            .comment("Largest pasture a player may mark out, in cells. Every animal inside one is looked",
                "over each time a herder takes stock, so a wide pen costs more to keep watch on than a",
                "tight one holding the same flock.")
            .defineInRange("maxPastureCells", 1_024, 1, 262_144);
        MAX_FARM_CELLS = BUILDER
            .comment("Largest field a player may mark out, in cells. A field is walked cell by cell when",
                "work is looked for, so raising this lets one zone stand in for several and makes each",
                "sweep over it longer.")
            .defineInRange("maxFarmCells", 4_096, 1, 262_144);
        MAX_PATROL_POINTS = BUILDER
            .comment("Most points a player may click to draw one patrol route. Patrollers stop along it",
                "no further apart than a ward reaches, and walk it end to end and back.")
            .defineInRange("maxPatrolPoints", 32, 2, 256);
        PATROL_WARD_RADIUS = BUILDER
            .comment("How far round a patrol stop, in blocks, monsters stop spawning once a patroller has",
                "stood watch there. It reaches as far up and down as it does across.")
            .defineInRange("patrolWardRadius", 16, 1, 128);
        PATROL_WARD_TICKS = BUILDER
            .comment("How long, in ticks, a stop stays warded after a patroller last stood watch there.",
                "Patrollers come back round to a stop once half of this has gone by.")
            .defineInRange("patrolWardTicks", 2_400, 20, 72_000);
        BUILDER.pop();

        BUILDER.push("blueprints");
        MAX_BUILD_CELLS = BUILDER
            .comment("Most cells one blueprint or drawn pattern may set, counting the cells it digs out",
                "as well as the blocks it lays, but not the empty air around them. A long wall or road",
                "with its cut and fill comes to some tens of thousands. Raising it lets bigger work",
                "through, at the cost of a longer standing order and more ghost blocks drawn in the world.")
            .defineInRange("maxBuildCells", 65_536, 1, 1_048_576);
        MAX_BLUEPRINT_VOLUME = BUILDER
            .comment("Widest span a blueprint may claim once read, in cells. This is the outer guard on a",
                "malformed or hostile structure file, not a building size: it stops one from claiming a",
                "volume too large to walk before its blocks are counted.")
            .defineInRange("maxBlueprintVolume", 1_048_576L, 1L, 16_777_216L);
        BUILDER.pop();

        BUILDER.push("perks");
        BUILDING_HASTE = BUILDER
            .comment("What one rank of the builder's quick hands does to the time a placement takes, read",
                "as a multiplier on the base wind-up: the first entry is rank 1, the second rank 2, and so",
                "on. Below 1 is faster, above 1 slower, and a rank past the end of the list counts as no",
                "change at all. Shorten the list to cap how far the perk carries.")
            .<Number>defineList("buildingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        CRAFTING_HASTE = BUILDER
            .comment("The crafter's quick hands, as a multiplier on the wind-up of a craft, a furnace load",
                "or a fetch from an output slot. One entry per rank, below 1 for faster.")
            .<Number>defineList("craftingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        CONDUCTING_HASTE = BUILDER
            .comment("The conductor's quick hands, as a multiplier on the wind-up of boarding and the rest",
                "of the rail work. One entry per rank, below 1 for faster.")
            .<Number>defineList("conductingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        DISPATCH_HASTE = BUILDER
            .comment("The courier's quick hands, as a multiplier on the wind-up of collecting a package.",
                "This ladder is deliberately shallower than the others: a courier's day is mostly walking,",
                "so a steep one would buy almost nothing. One entry per rank, below 1 for faster.")
            .<Number>defineList("dispatchHaste", List.of(0.85D, 0.72D, 0.62D, 0.55D), () -> 1.0D, HASTE_STEP);
        HERDING_HASTE = BUILDER
            .comment("The herder's quick hands, as a multiplier on the wind-up of shearing, milking,",
                "feeding and culling. One entry per rank, below 1 for faster.")
            .<Number>defineList("herdingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        FARMING_HASTE = BUILDER
            .comment("The farmer's quick hands, as a multiplier on the wind-up of tilling, sowing, reaping",
                "and felling. One entry per rank, below 1 for faster.")
            .<Number>defineList("farmingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
        FISHING_HASTE = BUILDER
            .comment("The fisher's quick hands, as a multiplier on how long a cast waits for a bite. One",
                "entry per rank, below 1 for faster.")
            .<Number>defineList("fishingHaste", List.of(0.85D, 0.70D, 0.55D, 0.40D), () -> 1.0D, HASTE_STEP);
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
        DISPATCH_REACH_TICKS = BUILDER
            .comment("Wind-up, in ticks, for a courier lifting a package off a port. Shorter than a",
                "hauler's reach by default, on the grounds that a port hands its package over ready to",
                "carry. The courier's quick hands scale it.")
            .defineInRange("dispatchReachTicks", 10, 0, 1_200);
        SEAT_TICKS = BUILDER
            .comment("Ticks a resident spends settling into a seat, whether that is a rail carriage or a",
                "courier's post. The same value is both the wind-up planned for and the count the sitting",
                "body waits out, so the plan and the body agree on what sitting down costs.")
            .defineInRange("seatTicks", 8, 0, 200);
        PLAN_HORIZON_TICKS = BUILDER
            .comment("How far ahead, in ticks, the schedule hands out work. Work that could start no sooner",
                "than this is left unassigned until a later plan, when whoever is free by then can take it:",
                "the colony plans again every few seconds, so a route laid further out only ties a resident",
                "to work they would wait on. Work that finishes something a resident has already begun is",
                "laid whatever its start.")
            .defineInRange("planHorizonTicks", 3_000, 200, 1_000_000);
        BUILDER.pop();

        BUILDER.push("navigation");
        PATH_SEARCH_BUDGET = BUILDER
            .comment("Multiplier on vanilla's node-expansion budget for a resident's pathfinding search.",
                "Higher routes through more convoluted structures and costs more per search. The cap on",
                "path *length* is the resident's follow range, a separate quantity.")
            .defineInRange("pathSearchBudget", 8.0D, 1.0D, 32.0D);
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
            : Math.clamp(Runtime.getRuntime().availableProcessors() / 4, 1, 4);
    }

    public static boolean immigrationEnabled() {
        return read(IMMIGRATION_ENABLED);
    }

    public static int foodPerResident() {
        return read(FOOD_PER_RESIDENT);
    }

    public static int settlerIntervalTicks() {
        return read(SETTLER_INTERVAL_TICKS);
    }

    public static int maxWaitingSettlers() {
        return read(MAX_WAITING_SETTLERS);
    }

    public static long maxFishCells() {
        return read(MAX_FISH_CELLS);
    }

    public static int maxPatrolPoints() {
        return read(MAX_PATROL_POINTS);
    }

    public static int patrolWardRadius() {
        return read(PATROL_WARD_RADIUS);
    }

    public static int patrolWardTicks() {
        return read(PATROL_WARD_TICKS);
    }

    public static int maxPastureCells() {
        return read(MAX_PASTURE_CELLS);
    }

    public static int maxFarmCells() {
        return read(MAX_FARM_CELLS);
    }

    public static int maxBuildCells() {
        return read(MAX_BUILD_CELLS);
    }

    public static long maxBlueprintVolume() {
        return read(MAX_BLUEPRINT_VOLUME);
    }

    public static List<? extends Number> buildingHaste() {
        return read(BUILDING_HASTE);
    }

    public static List<? extends Number> craftingHaste() {
        return read(CRAFTING_HASTE);
    }

    public static List<? extends Number> conductingHaste() {
        return read(CONDUCTING_HASTE);
    }

    public static List<? extends Number> dispatchHaste() {
        return read(DISPATCH_HASTE);
    }

    public static List<? extends Number> herdingHaste() {
        return read(HERDING_HASTE);
    }

    public static List<? extends Number> farmingHaste() {
        return read(FARMING_HASTE);
    }

    public static List<? extends Number> fishingHaste() {
        return read(FISHING_HASTE);
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

    public static int planHorizonTicks() {
        return read(PLAN_HORIZON_TICKS);
    }

    public static int dispatchReachTicks() {
        return read(DISPATCH_REACH_TICKS);
    }

    public static int seatTicks() {
        return read(SEAT_TICKS);
    }

    public static float pathSearchBudget() {
        return read(PATH_SEARCH_BUDGET).floatValue();
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
