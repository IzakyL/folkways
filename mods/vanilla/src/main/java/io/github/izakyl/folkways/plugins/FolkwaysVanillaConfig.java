package io.github.izakyl.folkways.plugins;

import io.github.izakyl.folkways.FolkwaysConfig;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.common.ModConfigSpec;

// The settings of the work residents do: zones, blueprints, crafting, settlers and the trades' quick hands.
public final class FolkwaysVanillaConfig {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    private static final Predicate<Object> HASTE_STEP = FolkwaysConfig.HASTE_STEP;

    private static final ModConfigSpec.BooleanValue IMMIGRATION_ENABLED;
    private static final ModConfigSpec.IntValue FOOD_PER_RESIDENT;
    private static final ModConfigSpec.IntValue SETTLER_INTERVAL_TICKS;
    private static final ModConfigSpec.IntValue MAX_WAITING_SETTLERS;
    private static final ModConfigSpec.IntValue MAX_DAMAGE_CONTROL_CELLS;
    private static final ModConfigSpec.IntValue DAMAGE_CONTROL_SCAN_BUDGET;
    private static final ModConfigSpec.IntValue DAMAGE_CONTROL_CONCURRENCY;
    private static final ModConfigSpec.IntValue MAX_FISH_CELLS;
    private static final ModConfigSpec.IntValue MAX_PASTURE_CELLS;
    private static final ModConfigSpec.IntValue MAX_FARM_CELLS;
    private static final ModConfigSpec.IntValue MAX_GROUNDWORK_CELLS;
    private static final ModConfigSpec.IntValue MAX_PATROL_POINTS;
    private static final ModConfigSpec.IntValue PATROL_WARD_RADIUS;
    private static final ModConfigSpec.IntValue PATROL_WARD_TICKS;
    private static final ModConfigSpec.IntValue MAX_SENTRY_CELLS;
    private static final ModConfigSpec.IntValue DEFENDER_SIGHT_RADIUS;
    private static final ModConfigSpec.DoubleValue SIGHT_HEIGHT_PER_BLOCK;
    private static final ModConfigSpec.IntValue SIGHT_HEIGHT_LIMIT;
    private static final ModConfigSpec.DoubleValue DARK_SIGHT_MULTIPLIER;
    private static final ModConfigSpec.IntValue DARK_SIGHT_LIGHT_LEVEL;
    private static final ModConfigSpec.IntValue FULL_SIGHT_LIGHT_LEVEL;
    private static final ModConfigSpec.DoubleValue OBSERVATION_SIGHT_MULTIPLIER;
    private static final ModConfigSpec.IntValue MINIMUM_SIGHT_RADIUS;
    private static final ModConfigSpec.IntValue MAXIMUM_SIGHT_RADIUS;
    private static final ModConfigSpec.IntValue SPOTTED_TARGET_TTL;
    private static final ModConfigSpec.IntValue MAX_SPOTTED_TARGETS;
    private static final ModConfigSpec.IntValue SENTRY_CALL_RADIUS;
    private static final ModConfigSpec.IntValue SENTRY_CALL_RESPONDERS;
    private static final ModConfigSpec.IntValue SENTRY_CALL_TICKS;
    private static final ModConfigSpec.IntValue MAX_BUILD_CELLS;
    private static final ModConfigSpec.BooleanValue SIMPLE_CONSTRUCTION;
    private static final ModConfigSpec.LongValue MAX_BLUEPRINT_VOLUME;
    private static final ModConfigSpec.IntValue DRAW_SECONDS;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> REFUSED_RECIPES;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> BUILDING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> CRAFTING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> HERDING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> FARMING_HASTE;
    private static final ModConfigSpec.ConfigValue<List<? extends Number>> FISHING_HASTE;
    private static final ModConfigSpec.DoubleValue PATH_SEARCH_BUDGET;
    private static final ModConfigSpec.IntValue DINING_MEAL_TICKS;

    public static final ModConfigSpec SPEC;

    static {

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

        BUILDER.push("damageControl");
        MAX_DAMAGE_CONTROL_CELLS = BUILDER.comment("Largest damage control zone, in cells.")
            .defineInRange("maxZoneCells", 32_768, 1, 262_144);
        DAMAGE_CONTROL_SCAN_BUDGET = BUILDER.comment("Cells inspected per colony per tick, shared by its zones.")
            .defineInRange("scanBudget", 512, 1, 16_384);
        DAMAGE_CONTROL_CONCURRENCY = BUILDER.comment("Most residents suppressing fires at once in one colony.")
            .defineInRange("concurrency", 4, 1, 64);
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
        MAX_GROUNDWORK_CELLS = BUILDER
            .comment("Largest box a player may mark out to clear, fill, weed, fell or haul from, in cells. It",
                "is walked cell by cell when work is looked for, so a deep box costs more to keep watch on",
                "than a shallow one.")
            .defineInRange("maxGroundworkCells", 16_384, 1, 262_144);
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

        BUILDER.push("sentries");
        MAX_SENTRY_CELLS = BUILDER
            .comment("Largest sentry zone in cells.")
            .defineInRange("maxSentryCells", 25, 1, 256);
        DEFENDER_SIGHT_RADIUS = BUILDER
            .comment("Baseline sight before vigilance, relative eye height, target brightness and observation gear.")
            .defineInRange("defenderSightRadius", 32, 1, 128);
        SIGHT_HEIGHT_PER_BLOCK = BUILDER
            .comment("Extra sight per block the observer's eyes are above the target's eyes.")
            .defineInRange("sightHeightPerBlock", 1.5, 0, 8);
        SIGHT_HEIGHT_LIMIT = BUILDER
            .comment("Maximum positive eye-height difference counted for sight, in blocks.")
            .defineInRange("sightHeightLimit", 16, 0, 64);
        DARK_SIGHT_MULTIPLIER = BUILDER
            .comment("Sight multiplier at zero effective target light; interpolated to one in bright light.")
            .defineInRange("darkSightMultiplier", .25, 0, 1);
        DARK_SIGHT_LIGHT_LEVEL = BUILDER
            .comment("Effective target light at or below which the darkest sight factor applies;",
                "capped below full light.")
            .defineInRange("darkSightLightLevel", 4, 0, 14);
        FULL_SIGHT_LIGHT_LEVEL = BUILDER
            .comment("Effective target light at which darkness no longer reduces sight.")
            .defineInRange("fullSightLightLevel", 12, 1, 15);
        OBSERVATION_SIGHT_MULTIPLIER = BUILDER
            .comment("Sight multiplier for a profile-required observation item actually in the pack; one disables it.")
            .defineInRange("observationSightMultiplier", 2.0, 1, 4);
        MINIMUM_SIGHT_RADIUS = BUILDER
            .comment("Minimum detection distance despite darkness, still requiring line of sight; capped by maximum.")
            .defineInRange("minimumSightRadius", 3, 1, 16);
        MAXIMUM_SIGHT_RADIUS = BUILDER
            .comment("Hard cap on all detection distances and entity scan bounds, in blocks.")
            .defineInRange("maximumSightRadius", 128, 1, 128);
        SPOTTED_TARGET_TTL = BUILDER
            .comment("Maximum observation age in ticks. Reports and calls are transient.")
            .defineInRange("spottedTargetTtl", 100, 1, 1200);
        MAX_SPOTTED_TARGETS = BUILDER
            .comment("Maximum observations kept by one colony.")
            .defineInRange("maxSpottedTargets", 128, 1, 4096);
        SENTRY_CALL_RADIUS = BUILDER
            .comment("Maximum distance from a sentry to a responding route patroller.")
            .defineInRange("sentryCallRadius", 48, 1, 256);
        SENTRY_CALL_RESPONDERS = BUILDER
            .comment("Maximum responding route patrollers per hostile.")
            .defineInRange("sentryCallResponders", 2, 1, 16);
        SENTRY_CALL_TICKS = BUILDER
            .comment("Maximum duration of one patrol diversion, in ticks.")
            .defineInRange("sentryCallTicks", 200, 20, 1200);
        BUILDER.pop();

        BUILDER.push("blueprints");
        SIMPLE_CONSTRUCTION = BUILDER
            .comment("Builders stop upon entering their work area and build in place without access scaffolds.",
                "Materials, tools and work time are unchanged. Read while the work runs; not stored in orders.")
            .define("simpleConstruction", false);
        MAX_BUILD_CELLS = BUILDER
            .comment("Most cells one blueprint or drawn pattern may set, counting the cells it digs out",
                "as well as the blocks it lays, but not the empty air around them. A long wall or road",
                "with its cut and fill comes to some tens of thousands. Raising it lets bigger work",
                "through, at the cost of a longer standing order and more ghost blocks drawn in the world.")
            .defineInRange("maxBuildCells", 262_144, 1, 4_194_304);
        MAX_BLUEPRINT_VOLUME = BUILDER
            .comment("Widest span a blueprint may claim once read, in cells. This is the outer guard on a",
                "malformed or hostile structure file, not a building size: it stops one from claiming a",
                "volume too large to walk before its blocks are counted.")
            .defineInRange("maxBlueprintVolume", 33_554_432L, 1L, 1_073_741_824L);
        DRAW_SECONDS = BUILDER
            .comment("Longest one pattern may take to draw, in seconds, before it is stopped and refused.",
                "Drawings run beside the game rather than in its tick, and patterns are trusted to read",
                "and draw what they like within what they capture: this is the one limit held over them.")
            .defineInRange("drawSeconds", 30, 1, 3_600);
        BUILDER.pop();

        BUILDER.push("crafting");
        REFUSED_RECIPES = BUILDER
            .comment("Recipes, by id, residents never craft, smelt or cut by, as though they did not exist:",
                "a recipe that wastes what it takes, or that a server wants kept to players. By default the",
                "wasteful ones: bamboo planks (nine bamboo for two planks), sticks from bamboo (two bamboo",
                "for one stick), smelting tools and armour back down to nuggets, taking waxed copper back",
                "to ingots (the wax is lost), and dyeing wool, beds and carpets that already have a colour",
                "into another (dye spent on colour already had). Read when the server starts.")
            .<String>defineListAllowEmpty("refusedRecipes", refusedByDefault(),
                () -> "minecraft:",
                id -> id instanceof String text && ResourceLocation.tryParse(text) != null);
        BUILDER.pop();

        BUILDER.push("perks");
        BUILDING_HASTE = BUILDER
            .comment("What one rank of the builder's quick hands does to the time a placement takes, read",
                "as a multiplier on the base wind-up: the first entry is rank 1, the second rank 2, and so",
                "on. Below 1 is faster, above 1 slower, and a rank past the end of the list counts as no",
                "change at all. Shorten the list to cap how far the perk carries.")
            .<Number>defineList("buildingHaste", List.of(1.0D / 1.1D, 1.0D / 1.2D, 1.0D / 1.35D), () -> 1.0D, HASTE_STEP);
        CRAFTING_HASTE = BUILDER
            .comment("The crafter's quick hands, as a multiplier on the wind-up of a craft, a furnace load",
                "or a fetch from an output slot. One entry per rank, below 1 for faster.")
            .<Number>defineList("craftingHaste", List.of(1.0D / 1.15D, 1.0D / 1.3D, 1.0D / 1.5D), () -> 1.0D, HASTE_STEP);
        HERDING_HASTE = BUILDER
            .comment("The herder's quick hands, as a multiplier on the wind-up of shearing, milking,",
                "feeding and culling. One entry per rank, below 1 for faster.")
            .<Number>defineList("herdingHaste", List.of(1.0D / 1.1D, 1.0D / 1.2D, 1.0D / 1.35D), () -> 1.0D, HASTE_STEP);
        FARMING_HASTE = BUILDER
            .comment("The farmer's quick hands, as a multiplier on the wind-up of tilling, sowing, reaping",
                "and felling. One entry per rank, below 1 for faster.")
            .<Number>defineList("farmingHaste", List.of(1.0D / 1.1D, 1.0D / 1.2D, 1.0D / 1.35D), () -> 1.0D, HASTE_STEP);
        FISHING_HASTE = BUILDER
            .comment("The fisher's quick hands, as a multiplier on how long a cast waits for a bite. One",
                "entry per rank, below 1 for faster.")
            .<Number>defineList("fishingHaste", List.of(0.9D, 0.8D, 0.7D), () -> 1.0D, HASTE_STEP);
        BUILDER.pop();

        BUILDER.push("navigation");
        PATH_SEARCH_BUDGET = BUILDER
            .comment("Multiplier on vanilla's node-expansion budget for a resident's pathfinding search.",
                "Higher routes through more convoluted structures and costs more per search. The cap on",
                "path *length* is the resident's follow range, a separate quantity.")
            .defineInRange("pathSearchBudget", 8.0D, 1.0D, 32.0D);
        BUILDER.pop();

        BUILDER.push("living");
        DINING_MEAL_TICKS = BUILDER
            .comment("Ticks a resident spends eating a serving at a dining table. Food is consumed only",
                "when the meal finishes; leaving early releases the seat and preserves the serving.")
            .defineInRange("diningMealTicks", 100, 20, 1200);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private FolkwaysVanillaConfig() {
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

    public static int maxDamageControlCells() {
        return read(MAX_DAMAGE_CONTROL_CELLS);
    }

    public static int damageControlScanBudget() {
        return read(DAMAGE_CONTROL_SCAN_BUDGET);
    }

    public static int damageControlConcurrency() {
        return read(DAMAGE_CONTROL_CONCURRENCY);
    }

    public static long maxFishCells() {
        return read(MAX_FISH_CELLS);
    }

    public static int maxSentryCells() {
        return read(MAX_SENTRY_CELLS);
    }

    public static int defenderSightRadius() {
        return read(DEFENDER_SIGHT_RADIUS);
    }

    public static double sightHeightPerBlock() {
        return read(SIGHT_HEIGHT_PER_BLOCK);
    }

    public static int sightHeightLimit() {
        return read(SIGHT_HEIGHT_LIMIT);
    }

    public static double darkSightMultiplier() {
        return read(DARK_SIGHT_MULTIPLIER);
    }

    public static int darkSightLightLevel() {
        return read(DARK_SIGHT_LIGHT_LEVEL);
    }

    public static int fullSightLightLevel() {
        return read(FULL_SIGHT_LIGHT_LEVEL);
    }

    public static double observationSightMultiplier() {
        return read(OBSERVATION_SIGHT_MULTIPLIER);
    }

    public static int minimumSightRadius() {
        return read(MINIMUM_SIGHT_RADIUS);
    }

    public static int maximumSightRadius() {
        return read(MAXIMUM_SIGHT_RADIUS);
    }

    public static int spottedTargetTtl() {
        return read(SPOTTED_TARGET_TTL);
    }

    public static int maxSpottedTargets() {
        return read(MAX_SPOTTED_TARGETS);
    }

    public static int sentryCallRadius() {
        return read(SENTRY_CALL_RADIUS);
    }

    public static int sentryCallResponders() {
        return read(SENTRY_CALL_RESPONDERS);
    }

    public static int sentryCallTicks() {
        return read(SENTRY_CALL_TICKS);
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

    public static int maxGroundworkCells() {
        return read(MAX_GROUNDWORK_CELLS);
    }

    public static int maxBuildCells() {
        return read(MAX_BUILD_CELLS);
    }

    public static boolean simpleConstruction() {
        return read(SIMPLE_CONSTRUCTION);
    }

    public static long maxBlueprintVolume() {
        return read(MAX_BLUEPRINT_VOLUME);
    }

    public static int drawSeconds() {
        return read(DRAW_SECONDS);
    }

    public static Set<ResourceLocation> refusedRecipes() {
        Set<ResourceLocation> refused = new LinkedHashSet<>();
        for (String id : read(REFUSED_RECIPES)) {
            ResourceLocation named = ResourceLocation.tryParse(id);
            if (named != null) {
                refused.add(named);
            }
        }
        return refused;
    }

    public static List<? extends Number> buildingHaste() {
        return read(BUILDING_HASTE);
    }

    public static List<? extends Number> craftingHaste() {
        return read(CRAFTING_HASTE);
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

    public static float pathSearchBudget() {
        return read(PATH_SEARCH_BUDGET).floatValue();
    }

    public static int diningMealTicks() {
        return read(DINING_MEAL_TICKS);
    }

    // The wasteful recipes refused unless a server says otherwise.
    private static List<String> refusedByDefault() {
        List<String> refused = new ArrayList<>(List.of(
            "minecraft:bamboo_planks",
            "minecraft:stick_from_bamboo_item",
            "minecraft:iron_nugget_from_smelting",
            "minecraft:iron_nugget_from_blasting",
            "minecraft:gold_nugget_from_smelting",
            "minecraft:gold_nugget_from_blasting",
            "minecraft:copper_ingot_from_waxed_copper_block"));
        for (DyeColor colour : DyeColor.values()) {
            for (String dyed : List.of("wool", "bed", "carpet")) {
                refused.add("minecraft:dye_" + colour.getName() + "_" + dyed);
            }
        }
        return List.copyOf(refused);
    }

    private static <T> T read(ModConfigSpec.ConfigValue<T> value) {
        return SPEC.isLoaded() ? value.get() : value.getDefault();
    }
}
