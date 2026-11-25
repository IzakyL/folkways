package io.github.izakyl.folkways.airline;

import io.github.izakyl.folkways.core.front.api.Schema;
import io.github.izakyl.folkways.core.front.api.Settings;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.vanilla.build.Blueprint;
import io.github.izakyl.folkways.vanilla.build.StructureNbt;
import io.github.izakyl.folkways.vanilla.build.draft.Commission;
import io.github.izakyl.folkways.vanilla.build.draft.DraftTemplate;
import io.github.izakyl.folkways.vanilla.build.draft.Drafting;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import io.github.izakyl.folkways.vanilla.build.draft.Hint;
import io.github.izakyl.folkways.vanilla.build.draft.Pattern;
import io.github.izakyl.folkways.vanilla.build.draft.Patterns;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = FolkwaysAirlineMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_airline")
@PrefixGameTestTemplate(false)
public final class AirlinePatternGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger("airline-pattern-tests");
    /** The conventional three-degree glide slope that runway approach clearance planes stay below. */
    private static final double THREE_DEGREES = Math.toRadians(3);

    private AirlinePatternGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AirlinePatternGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_0", timeoutTicks = 600)
    public static void runwayDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.set(f.at(0, -19).above(3), Blocks.STONE.defaultBlockState());
            f.set(f.at(1, -19).below(), Blocks.AIR.defaultBlockState());
            f.set(f.at(1, -19).below(2), Blocks.STONE.defaultBlockState());
            f.runway(false);
            helper.assertTrue(f.level.getBlockState(f.at(0, -19).above(3)).isAir(), "grading cuts the mound");
            helper.assertTrue(f.level.getBlockState(f.at(1, -19).below()).is(Blocks.GRAY_CONCRETE),
                "grading fills the foundation");
            Airport airport = f.scan();
            helper.assertValueEqual(airport.runways().size(), 1, "one drafted runway");
            helper.assertValueEqual(airport.runways().getFirst().length(), 48, "inclusive runway length");
            f.clean(airport);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_0")
    public static void airlineResourcesLoadGradingAndAllLocales(GameTestHelper helper) {
        helper.assertTrue(Patterns.sources().containsKey(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "grading")),
            "the vanilla grading library is available across namespaces");
        for (String name : List.of("runway", "approach_clearance", "taxiway", "apron", "helipad", "airport_tower")) {
            Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, name))
                .orElseThrow();
            for (String locale : List.of("en_us", "zh_cn", "zh_hk", "zh_tw")) {
                Map<String, String> words = pattern.face().words().get(locale);
                helper.assertTrue(words != null && words.containsKey("title"), name + " title in " + locale);
                helper.assertTrue(words.containsKey("error.zone"), name + " inherits the airline library in " + locale);
                for (Schema.Setting setting : pattern.knobs().settings()) {
                    helper.assertTrue(words.containsKey(setting.key()), name + " knob in " + locale);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_0", timeoutTicks = 600)
    public static void taxiwayCrossingDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.pad(-30, 0, 7, "ns", "thin");
            f.pad(30, 0, 7, "ew", "thin");
            f.pad(0, -30, 7, "ns", "thin");
            f.pad(0, 30, 7, "ew", "thin");
            f.draw("taxiway", f.path(-26, 0, 26, 0), Map.of("width", 5));
            f.draw("taxiway", f.path(0, -26, 0, 26), Map.of("width", 5));
            Airport airport = f.scan();
            f.clean(airport);
            helper.assertValueEqual(airport.ground().nodes().size(), 4, "facilities share an open taxi area");
            Set<BlockPos> reachable = airport.ground().reachableFrom(Set.of(f.at(-30, 0)));
            helper.assertTrue(airport.ground().nodes().stream()
                .filter(node -> node.kinds().contains(GroundGraph.Kind.HELIPAD))
                .allMatch(node -> reachable.contains(node.pos())), "all four helipads connected through crossing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_1", timeoutTicks = 600)
    public static void northSouthRunwayDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            for (int z = -32 - Airport.APPROACH_LENGTH; z <= 31 + Airport.APPROACH_LENGTH; z++) {
                for (int x = -3; x <= 3; x++) {
                    f.force(f.at(x, z));
                }
            }
            f.draw("runway", f.path(0, 31, 0, -32), Map.of("width", 7, "clearance", true, "entries", false));
            Airport airport = f.scan();
            f.clean(airport);
            helper.assertValueEqual(airport.runways().size(), 1, "reverse north–south runway");
            helper.assertValueEqual(airport.runways().getFirst().width(), 7, "selected runway width");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_1", timeoutTicks = 600)
    public static void approachClearanceDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.runway(false);
            f.obstructions(5);
            // Observe from beside the strip: approach obstacles must not hide the runway from its tower.
            f.tower = f.at(0, 80).above(43);
            for (int x = (f.tower.getX() - AirportScan.VALIDATION_RADIUS) >> 4;
                    x <= (f.tower.getX() + AirportScan.VALIDATION_RADIUS) >> 4; x++) {
                for (int z = (f.tower.getZ() - AirportScan.VALIDATION_RADIUS) >> 4;
                        z <= (f.tower.getZ() + AirportScan.VALIDATION_RADIUS) >> 4; z++) {
                    f.force(new BlockPos(x << 4, f.tower.getY(), z << 4));
                }
            }
            f.set(f.tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
                .setValue(AirportTowerBlock.FACING, Direction.SOUTH));
            helper.assertValueEqual(f.scan().flaws().stream()
                .filter(p -> p.kind() == AirportFlaw.APPROACH_OBSTRUCTED).count(), 2L, "both ends obstructed");
            f.draw("approach_clearance", f.path(-24, -20, 23, -20), Map.of());
            f.clean(f.scan());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_1", timeoutTicks = 600)
    public static void taxiwayDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.runway(false);
            f.pad(0, 20, 7, "ns", "thin");
            Drawn.Ready taxiway = f.draw("taxiway", f.path(0, -17, 0, 16), Map.of("width", 5));
            helper.assertValueEqual(taxiway.reports().get("max_wingspan"), 3.0, "offered taxi grade");
            Airport airport = f.scan();
            f.clean(airport);
            helper.assertTrue(airport.ground().nodes().stream()
                .anyMatch(node -> node.kinds().contains(GroundGraph.Kind.RUNWAY_ENTRY)), "runway access generated");
            helper.assertTrue(airport.ground().reachableFrom(Set.of(airport.runways().getFirst().firstThreshold()))
                .contains(airport.ground().nodes().stream()
                    .filter(node -> node.kinds().contains(GroundGraph.Kind.HELIPAD))
                    .findFirst().orElseThrow().pos()), "drafted centerline connects landing areas");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_2", timeoutTicks = 600)
    public static void apronDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.runway(false);
            for (int x = 9; x <= 28; x++) {
                for (int z = -5; z <= 10; z++) {
                    f.set(f.at(x, z), Blocks.GRAY_CONCRETE.defaultBlockState());
                }
            }
            f.apron();
            f.apronConnection();
            Airport airport = f.scan();
            f.assertStands(airport);
            f.clean(airport);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_2", timeoutTicks = 600)
    public static void helipadDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            int index = 0;
            for (int size : new int[] {7, 31}) {
                for (String heading : List.of("ns", "ew")) {
                    for (String stroke : List.of("plain", "circle")) {
                        int x = -60 + index % 4 * 40;
                        int z = -40 + index / 4 * 40;
                        f.pad(x, z, size, heading, stroke);
                        index++;
                    }
                }
            }
            Airport airport = f.scan();
            helper.assertValueEqual(airport.helipads().size(), 8,
                "all sizes, orientations and stroke styles: " + airport.helipads() + " flaws=" + airport.flaws());
            helper.assertValueEqual(airport.ground().nodes().size(), 8, "only facility nodes");
            f.clean(airport);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_2", timeoutTicks = 600)
    public static void towerDraftRoundTrip(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.pad(0, 20, 7, "ns", "thin");
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                f.tower(direction.getName());
                helper.assertValueEqual(f.level.getBlockState(f.tower).getValue(AirportTowerBlock.FACING), direction,
                    "draft preserves cardinal tower heading");
                helper.assertTrue(f.level.getBlockState(f.tower.relative(direction)).getBlock()
                    instanceof com.simibubi.create.content.contraptions.actors.seat.SeatBlock,
                    "each heading puts the duty seat in front");
            }
            f.tower();
            helper.assertTrue(Airports.validTower(f.level, f.tower), "draft places the real tower block on ground");
            helper.assertValueEqual(f.level.getBlockState(f.tower).getValue(AirportTowerBlock.FACING), Direction.WEST,
                "draft preserves the chosen tower heading");
            helper.assertTrue(f.level.getBlockState(f.tower.west()).getBlock()
                instanceof com.simibubi.create.content.contraptions.actors.seat.SeatBlock,
                "duty seat is immediately in front of the tower");
            helper.assertTrue(f.level.getBlockState(f.tower.offset(-1, -1, 1)).is(Blocks.SCAFFOLDING),
                "residents can climb the access shaft to the top platform");
            Airports.register(f.level, f.tower);
            helper.assertTrue(Airports.at(f.level, f.tower).isPresent(), "tower registers as an airport");
            f.clean(f.scan());
            f.level.removeBlock(f.tower, false);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_3", timeoutTicks = 1200)
    public static void combinedAirportDraftRoundTrip(GameTestHelper helper) {
        Fixture f = new Fixture(helper);
        try {
            f.runway(true);
            f.draw("approach_clearance", f.path(-24, -20, 23, -20), Map.of());
            f.apron();
            f.apronConnection();
            f.pad(-20, 20, 7, "ew", "thin");
            f.draw("taxiway", f.path(0, 8, -20, 8, -20, 16), Map.of("width", 3));
            // Crossing an existing line must preserve its yellow cells and produce one legal junction.
            f.tower();
            Airports.register(f.level, f.tower);
            Airports.rename(f.level, f.tower, AirlineTestArea.of(helper).name("Draft " + f.tower.toShortString()));
            Airports.request(f.level, f.tower);
            helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
                !Airports.scanning(f.level, f.tower), "combined airport scan finished")).thenExecute(() -> {
                try {
                    Airport airport = Airports.at(f.level, f.tower).orElseThrow();
                    f.clean(airport);
                    helper.assertValueEqual(airport.runways().size(), 1, "combined runway");
                    helper.assertValueEqual(airport.helipads().size(), 1, "combined helipad");
                    f.assertStands(airport);
                    Airport.Candidates candidates = airport.candidates(new AirportRequirements(7, 7, 3, true));
                    helper.assertValueEqual(candidates.helipads().size(), 1, "helipad candidate");
                    helper.assertValueEqual(candidates.stands().size(), 2, "both stands reachable and available");
                } finally {
                    f.level.removeBlock(f.tower, false);
                    f.close();
                }
            }).thenSucceed();
        } catch (Throwable failed) {
            f.close();
            throw failed;
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_3", timeoutTicks = 600)
    public static void approachGeometryMatchesJava(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.runway(false);
            BlockPos lowObstacle = f.at(-24 - 80, -20).above(6);
            f.set(lowObstacle, Blocks.STONE.defaultBlockState());
            helper.assertTrue(7 < 80.0 / 8 && 7 > 80 * Math.tan(THREE_DEGREES),
                "obstacle is below the old 1:8 plane but intersects the three-degree approach");
            helper.assertTrue(f.scan().flaws().stream()
                .anyMatch(problem -> problem.kind() == AirportFlaw.APPROACH_OBSTRUCTED),
                "scanner rejects a low obstacle that the old slope admitted");
            f.obstructions(5);
            BlockPos outside = f.at(-25 - Airport.APPROACH_LENGTH, -20).above(40);
            f.set(outside, Blocks.STONE.defaultBlockState());
            Drawn.Ready ready = f.draw("approach_clearance", f.path(-24, -20, 23, -20), Map.of());
            helper.assertValueEqual(ready.reports().get("approach_length"), (double) Airport.APPROACH_LENGTH,
                "Starlark length equals Java constant");
            helper.assertValueEqual(ready.reports().get("approach_slope"), (double) Airport.APPROACH_SLOPE,
                "Starlark slope equals Java constant");
            helper.assertTrue(1.0 / Airport.APPROACH_SLOPE <= Math.tan(THREE_DEGREES),
                "clearance plane never rises above a three-degree glide slope");
            helper.assertTrue(f.level.getBlockState(lowObstacle).isAir(), "unsafe low obstacle cleared");
            helper.assertTrue(ready.reports().get("clearance_cells") > 0, "draft reports clearance excavation");
            for (int end : new int[] {-24, 23}) {
                int sign = end < 0 ? -1 : 1;
                for (int distance = 1; distance <= Airport.APPROACH_LENGTH; distance++) {
                    int allowed = distance / Airport.APPROACH_SLOPE;
                    for (int side = -2; side <= 2; side++) {
                        BlockPos safe = f.at(end + sign * distance, -20 + side).above(allowed);
                        helper.assertTrue(f.level.getBlockState(safe).is(Blocks.STONE),
                            "preserve plane boundary at " + distance + ", " + side);
                        helper.assertTrue(f.level.getBlockState(safe.above()).isAir(),
                            "clear immediately above plane at " + distance + ", " + side);
                    }
                }
            }
            helper.assertTrue(f.level.getBlockState(outside).is(Blocks.STONE), "length plus one stays untouched");
            f.clean(f.scan());
        }
        helper.succeed();
    }

    // Exclusive: asserts the real drafting wall-clock deadline at maximum geometry.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "pattern_scale", timeoutTicks = 1200)
    public static void maximumRunwayAndClearanceWithinDraftDeadline(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.ground(-40, -40, 550, 40);
            for (int end : new int[] {0, 511}) {
                int sign = end == 0 ? -1 : 1;
                for (int distance = 1; distance <= Airport.APPROACH_LENGTH; distance++) {
                    for (int side = -15; side <= 15; side++) {
                        f.force(f.at(end + sign * distance, side));
                        f.set(f.at(end + sign * distance, side)
                            .above(distance / Airport.APPROACH_SLOPE + 3), Blocks.STONE.defaultBlockState());
                    }
                }
            }
            long start = System.nanoTime();
            Drawn.Ready ready = f.draw("runway", f.path(0, 0, 511, 0), Map.of("width", 31, "clearance", true, "entries", false));
            long milliseconds = (System.nanoTime() - start) / 1_000_000;
            LOGGER.info("AIRLINE_SCALE length=512 width=31 approaches={} cells={} capture_draw_nbt_place_ms={} "
                    + "deadline_s={} capture_and_timed_draft_ms={}",
                Airport.APPROACH_LENGTH, ready.draft().cells().size(), milliseconds,
                FolkwaysVanillaConfig.drawSeconds(), f.lastDraftMilliseconds);
            helper.assertTrue(milliseconds < FolkwaysVanillaConfig.drawSeconds() * 1000L,
                "capture, timed draft, NBT and placement all finish within the default draft deadline");
            helper.assertValueEqual(Runways.at(f.level, f.at(256, 0)).orElseThrow().length(), 512,
                "maximum runway remains recognizable");
            for (int end : new int[] {0, 511}) {
                int sign = end == 0 ? -1 : 1;
                for (int distance = 1; distance <= Airport.APPROACH_LENGTH; distance++) {
                    for (int side = -15; side <= 15; side++) {
                        helper.assertTrue(f.level.getBlockState(f.at(end + sign * distance, side)
                            .above(distance / Airport.APPROACH_SLOPE + 3)).isAir(), "maximum approach cleared");
                    }
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_3", timeoutTicks = 600)
    public static void invalidPathsAndHoldingBranchesAreRefused(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.refused("runway", f.path(0, 0, 20, 0), Map.of(), "error.runway.length");
            f.refused("runway", f.path(0, 0, 513, 0), Map.of(), "error.runway.length");
            f.refused("runway", new Hint.Path(List.of(f.at(0, 0), f.at(48, 0).above())), Map.of(),
                "error.runway.points");
            f.refused("runway", f.path(0, 0, 48, 0), Map.of("width", 6), "error.width");
            f.refused("taxiway", f.path(0, 0, 0, 0), Map.of(), "error.taxiway.path");
            f.runway(false);
            f.set(f.at(1, -15), Blocks.YELLOW_CONCRETE.defaultBlockState());
            f.draw("taxiway", f.path(0, -17, 0, 0), Map.of("centerline", false));
        }
        helper.succeed();
    }



    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_patterns_4", timeoutTicks = 1200)
    public static void apronGradesRespectBaySettings(GameTestHelper helper) {
        for (int size : new int[] {7, 9, 11, 15, 23, 31}) {
            try (Fixture f = new Fixture(helper)) {
                Drawn.Ready apron = f.draw("apron", new Hint.Zone(f.at(0, 0),
                    f.at((size + 2) * 2 - 1, size + Math.max(7, size / 2) + 3)), Map.of("size", size));
                helper.assertValueEqual(apron.reports().get("max_wingspan"), (double) size, "offered stand grade");
                helper.assertValueEqual(apron.reports().get("max_length"), (double) (size + Math.max(7, size / 2)), "offered stand length");
                Airport airport = f.scan();
                helper.assertValueEqual(airport.stands().size(), 2, "two stands at size " + size);
                for (Airport.Stand stand : airport.stands()) {
                    helper.assertValueEqual(stand.length(), size + Math.max(7, size / 2) + 2, "bay length at size " + size);
                    helper.assertValueEqual(stand.width(), size + 2, "bay width at size " + size);
                    helper.assertValueEqual(stand.turningRadius(), (size + 2) / 2.0 - AirportGrading.MARGIN,
                        "advertised turning circle at size " + size);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "diagonal_patterns_0", timeoutTicks = 600)
    public static void diagonalRunwayPatternThirtyDegrees(GameTestHelper helper) {
        diagonalPattern(helper, 32, -55, "03", "21");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "diagonal_patterns_1", timeoutTicks = 600)
    public static void diagonalRunwayPatternFortyFiveDegrees(GameTestHelper helper) {
        diagonalPattern(helper, 45, -45, "05", "23");
    }

    private static void diagonalPattern(GameTestHelper helper, int x, int z, String first, String second) {
        try (Fixture f = new Fixture(helper)) {
            f.draw("runway", f.path(0, 25, x, 25 + z), Map.of("width", 15, "entries", false));
            Airport airport = f.scan();
            helper.assertValueEqual(airport.runways().size(), 1, "diagonal runway surveyed: " + airport.flaws());
            Runway runway = airport.runways().getFirst();
            helper.assertValueEqual(runway.length(), (int) Math.round(Math.hypot(x, z)) + 1, "inclusive length");
            helper.assertValueEqual(runway.width(), 15, "rasterized width");
            helper.assertTrue(airport.ends().stream().anyMatch(e -> e.number().equals(first)), "forward number");
            helper.assertTrue(airport.ends().stream().anyMatch(e -> e.number().equals(second)), "reciprocal number");
            f.clean(airport);
            Airports.register(f.level, airport.tower());
            Airports.rename(f.level, airport.tower(), AirlineTestArea.of(helper).name("Diagonal"));
            AirportRegistry.of(f.level).cache(airport);
            var sites = AirportLandingSites.sites(f.level).stream()
                .filter(site -> site.airport().id().equals(
                    io.github.izakyl.folkways.core.world.api.WorldSpaces.at(f.level, airport.tower()).toString())).toList();
            helper.assertValueEqual(sites.size(), 2, "both diagonal landing sites exported");
            double heading = Math.toDegrees(Math.atan2(x, -z));
            helper.assertTrue(sites.stream().anyMatch(site -> Math.abs(site.yaw() - (heading - 180)) < 0.001),
                "exact threshold-derived landing yaw");
            helper.assertValueEqual(AirportData.read(AirportData.write(airport)), airport, "oriented NBT round trip");
            BlockPos obstacle = f.at(0, 25).offset((int) Math.round(-x / Math.hypot(x, z) * 40), 3,
                (int) Math.round(-z / Math.hypot(x, z) * 40));
            f.set(obstacle, Blocks.STONE.defaultBlockState());
            helper.assertTrue(f.scan().flaws().stream().anyMatch(p -> p.kind() == AirportFlaw.APPROACH_OBSTRUCTED),
                "diagonal obstacle intersects the 1:20 plane");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "diagonal_patterns_2", timeoutTicks = 600)
    public static void diagonalTaxiwayPatternConnectsStand(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.draw("runway", f.path(-40, -40, 20, 20), Map.of("width", 15, "entries", false));
            f.draw("apron", new Hint.Zone(f.at(1, 43), f.at(18, 60)), Map.of("size", 7));
            // Cardinal terminal bays provide holding and stand connections; the middle is a staircase.
            f.draw("taxiway", f.path(-20, -9, -20, 0, -5, 15, -5, 55, 0, 55), Map.of("width", 5));
            Airport airport = f.scan();
            helper.assertValueEqual(airport.runways().size(), 1, "diagonal runway with entrance");
            helper.assertTrue(!airport.stands().isEmpty(), "drafted stand");
            helper.assertTrue(airport.flaws().stream().noneMatch(p -> p.kind() == AirportFlaw.STAND_UNREACHABLE),
                "diagonal graph grades: " + airport.flaws());
            helper.assertTrue(airport.stands().stream().allMatch(stand -> airport.ground()
                .reachableFrom(Set.of(airport.runways().getFirst().firstThreshold())).contains(stand.pos())),
                "stand reaches diagonal runway");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "diagonal_patterns_3", timeoutTicks = 600)
    public static void diagonalParallelRunwayDesignators(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.tower = f.at(-140, -100).above(43);
            for (int x = -544; x <= 256; x += 16) {
                for (int z = -512; z <= -400; z += 16) {
                    f.force(f.at(x, z));
                }
            }
            for (int i = 0; i < 5; i++) {
                f.draw("runway", f.path(-32, -40 + i * 20, 31, -40 + i * 20),
                    Map.of("width", 5, "entries", false));
                if (i == 0) {
                    continue;
                }
                f.draw("taxiway", f.path(0, -40, 0, -40 + i * 20), Map.of("width", 5));
                Airport airport = f.scan();
                helper.assertValueEqual(airport.runways().size(), i + 1, "all parallels surveyed");
                f.clean(airport);
                Set<String> expected = switch (i) {
                    case 1 -> Set.of("09L", "09R", "27L", "27R");
                    case 2 -> Set.of("09L", "09C", "09R", "27L", "27C", "27R");
                    case 3 -> Set.of("09L", "09R", "10L", "10R", "27L", "27R", "28L", "28R");
                    default -> Set.of("09L", "09R", "10L", "10R", "08", "27L", "27R", "28L", "28R", "26");
                };
                helper.assertValueEqual(airport.ends().stream().map(Airport.End::number)
                    .collect(java.util.stream.Collectors.toSet()), expected, "parallel numbering");
                for (Runway runway : airport.runways()) {
                    String a = airport.designator(runway, runway.firstThreshold());
                    String b = airport.designator(runway, runway.secondThreshold());
                    if (a.endsWith("L")) {
                        helper.assertTrue(b.endsWith("R"), "reciprocal mirrors L/R");
                    }
                }
                Airports.register(f.level, airport.tower());
                Airports.rename(f.level, airport.tower(), AirlineTestArea.of(helper).name("Parallels"));
                AirportRegistry.of(f.level).cache(airport);
                var sites = AirportLandingSites.sites(f.level).stream().filter(site -> site.airport().id().equals(
                    io.github.izakyl.folkways.core.world.api.WorldSpaces.at(f.level, airport.tower()).toString())).toList();
                helper.assertValueEqual(sites.size(), (i + 1) * 2, "all parallel landing sites exported");
                helper.assertValueEqual(sites.stream().map(site -> site.id()).distinct().count(), (long) sites.size(),
                    "unique landing site ids");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "diagonal_patterns_4", timeoutTicks = 600)
    public static void diagonalRunwayPatternEntries(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            f.draw("runway", f.path(-30, 30, 15, -48), Map.of("width", 15, "taxi_width", 5, "entries", true));
            Airport airport = f.scan();
            helper.assertValueEqual(airport.runways().size(), 1, "diagonal runway with rotated entries");
            helper.assertTrue(airport.ground().nodes().stream()
                .anyMatch(node -> node.kinds().contains(GroundGraph.Kind.RUNWAY_ENTRY)), "entries touch runway");
            f.clean(airport);
        }
        helper.succeed();
    }

    private static final class Fixture implements AutoCloseable {
        private final GameTestHelper helper;
        private final ServerLevel level;
        private final BlockPos base;
        private final Set<ChunkPos> forced = new HashSet<>();
        private final Map<BlockPos, BlockState> originals = new HashMap<>();
        private BlockPos tower;
        private long lastDraftMilliseconds;
        private boolean closed;

        private Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            var area = AirlineTestArea.of(helper);
            base = area.pos(0, 100, 0);
            area.onClose(this::close);
            tower = at(-140, 0).above(43);
            for (int x = -544; x <= 256; x += 16) {
                for (int z = -400; z <= 400; z += 16) {
                    force(at(x, z));
                }
            }
            ground(-80, -80, 80, 80);
        }

        private BlockPos at(int x, int z) {
            return base.offset(x, 0, z);
        }

        private void force(BlockPos pos) {
            ChunkPos chunk = new ChunkPos(pos);
            if (forced.add(chunk)) {
                level.setChunkForced(chunk.x, chunk.z, true);
                level.getChunk(chunk.x, chunk.z);
            }
        }

        private void ground(int minX, int minZ, int maxX, int maxZ) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    force(at(x, z));
                    set(at(x, z).below(), Blocks.STONE.defaultBlockState());
                }
            }
        }

        private Hint.Path path(int... xz) {
            var points = new java.util.ArrayList<BlockPos>();
            for (int i = 0; i < xz.length; i += 2) {
                points.add(at(xz[i], xz[i + 1]));
            }
            return new Hint.Path(points);
        }

        private Drawn result(String name, Hint hint, Map<String, Object> overrides) {
            Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, name))
                .orElseThrow(() -> new AssertionError("airline resource pattern was not loaded: " + name));
            long began = System.nanoTime();
            Drawn result = Drafting.draw(pattern, new Commission(hint, new Defaults(pattern.knobs(), overrides),
                pattern.capture(level, hint), Direction.NORTH, 42L)).join();
            lastDraftMilliseconds = (System.nanoTime() - began) / 1_000_000;
            return result;
        }

        private Drawn.Ready draw(String name, Hint hint, Map<String, Object> overrides) {
            Drawn result = result(name, hint, overrides);
            helper.assertTrue(result instanceof Drawn.Ready, name + " real timed draft: " + result);
            Drawn.Ready ready = (Drawn.Ready) result;
            Blueprint blueprint = StructureNbt.toBlueprint(DraftTemplate.of(ready.draft(), 0), level.registryAccess(),
                UUID.randomUUID(), name, level.getGameTime(), StructureNbt.AirMeaning.EXCAVATE, 262144)
                .orElseThrow(() -> new AssertionError(name + " failed the real NBT handoff")).confirm(level);
            helper.assertValueEqual(blueprint.blocks().size(), ready.draft().cells().size(), "NBT keeps every cell");
            for (var cell : blueprint.blocks()) {
                BlockPos pos = cell.worldPos(ready.corner());
                force(pos);
                set(pos, cell.state());
            }
            return ready;
        }

        private void refused(String name, Hint hint, Map<String, Object> overrides, String key) {
            Drawn result = result(name, hint, overrides);
            helper.assertTrue(result instanceof Drawn.Refused,
                name + " refuses " + key + ": " + result.getClass().getSimpleName());
            Drawn.Refused refused = (Drawn.Refused) result;
            helper.assertTrue(refused.explanation().isPresent() && refused.detail().contains(key),
                "expected translated refusal " + key + ": " + refused);
        }

        private void runway(boolean clearance) {
            for (int x = -24 - Airport.APPROACH_LENGTH; x <= 23 + Airport.APPROACH_LENGTH; x++) {
                for (int side = -2; side <= 2; side++) {
                    force(at(x, -20 + side));
                }
            }
            draw("runway", path(-24, -20, 23, -20), Map.of("width", 5, "clearance", clearance, "entries", false));
        }

        private void obstructions(int width) {
            for (int end : new int[] {-24, 23}) {
                int sign = end < 0 ? -1 : 1;
                for (int distance = 1; distance <= Airport.APPROACH_LENGTH; distance++) {
                    for (int side = -width / 2; side <= width / 2; side++) {
                        BlockPos pos = at(end + sign * distance, -20 + side);
                        force(pos);
                        int allowed = distance / Airport.APPROACH_SLOPE;
                        set(pos.above(allowed), Blocks.STONE.defaultBlockState());
                        set(pos.above(allowed + 1), Blocks.STONE.defaultBlockState());
                        set(pos.above(allowed + 4), Blocks.SHORT_GRASS.defaultBlockState());
                    }
                }
            }
        }

        private void pad(int x, int z, int size, String heading, String stroke) {
            int half = size / 2;
            draw("helipad", new Hint.Zone(at(x - half, z - half), at(x + half, z + half)),
                Map.of("size", size, "heading", option(heading), "circle", stroke.equals("circle")));
            helper.assertTrue(Helipads.atCenter(level, at(x, z)).isPresent(),
                "drafted pad " + size + " " + heading + " " + stroke + " at " + x + ", " + z);
        }

        private void apron() {
            draw("apron", new Hint.Zone(at(10, -4), at(27, 13)), Map.of("size", 7));
        }

        private void apronConnection() {
            draw("taxiway", path(0, -17, 0, 8, 9, 8), Map.of("width", 3));
        }

        private void tower() {
            tower("west");
        }

        private void tower(String heading) {
            draw("airport_tower", new Hint.Zone(at(-141, -1), at(-139, 1)),
                Map.of("height", 44, "heading", option(heading)));
        }

        private Airport scan() {
            if (!Airports.validTower(level, tower)) {
                set(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
                    .setValue(AirportTowerBlock.FACING, Direction.WEST));
            }
            AirportScan scan = new AirportScan(level, tower);
            for (int step = 0; step < 4000000; step++) {
                if (scan.advance(Runnable::run)) {
                    return scan.result();
                }
            }
            throw new AssertionError("AirportScan did not finish within four million steps");
        }

        private void clean(Airport airport) {
            helper.assertTrue(airport.flaws().stream().allMatch(p -> p.kind() == AirportFlaw.RUNWAY_UNREACHABLE),
                "no unexpected airport flaws: " + airport.flaws());
        }

        private void assertStands(Airport airport) {
            helper.assertValueEqual(airport.stands().size(), 2, "two drafted stands");
            helper.assertTrue(!airport.stands().get(0).footprint().overlaps(airport.stands().get(1).footprint()),
                "maximum rectangles do not overlap");
            for (Airport.Stand stand : airport.stands()) {
                helper.assertTrue(stand.length() >= 16, "parking depth includes adjoining pavement");
                helper.assertValueEqual(stand.facing(), Direction.NORTH, "stand noses face the terminal side");
                helper.assertValueEqual(stand.width(), 9, "maximum stand width");
                helper.assertTrue(airport.ground().reachableFrom(Set.of(airport.runways().getFirst().firstThreshold()))
                    .contains(stand.pos()), "stand reachable from runway");
            }
        }

        private void set(BlockPos pos, BlockState state) {
            originals.putIfAbsent(pos.immutable(), level.getBlockState(pos));
            level.setBlock(pos, state, 2);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            originals.forEach((pos, state) -> {
                Airports.cancelSurvey(level, pos);
                Airports.remove(level, pos);
            });
            originals.forEach((pos, state) -> level.setBlock(pos, state, 2));
            forced.forEach(chunk -> TowerTestSite.releaseChunk(level, chunk));
        }
    }

    static void place(GameTestHelper helper, TowerTestSite site, String name, Hint hint,
                      Map<String, Object> overrides) {
        place(helper, site, name, hint, overrides, Direction.NORTH);
    }

    static void place(GameTestHelper helper, TowerTestSite site, String name, Hint hint,
                      Map<String, Object> overrides, Direction heading) {
        Pattern pattern = Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysAirlineMod.ID, name))
            .orElseThrow();
        Drawn drawn = Drafting.draw(pattern, new Commission(hint, new Defaults(pattern.knobs(), overrides),
            pattern.capture(site.level, hint), heading, 42L)).join();
        helper.assertTrue(drawn instanceof Drawn.Ready, "native airport pattern " + name + ": " + drawn);
        Drawn.Ready ready = (Drawn.Ready) drawn;
        var blueprint = StructureNbt.toBlueprint(DraftTemplate.of(ready.draft(), 0), site.level.registryAccess(),
            UUID.randomUUID(), name, site.level.getGameTime(), StructureNbt.AirMeaning.EXCAVATE, 262144)
            .orElseThrow().confirm(site.level);
        for (var cell : blueprint.blocks()) {
            var pos = cell.worldPos(ready.corner());
            site.set(pos, cell.state());
        }
    }

    private static ResourceLocation option(String local) {
        return ResourceLocation.fromNamespaceAndPath("pattern", local);
    }

    private record Defaults(Map<String, Object> values) implements Settings {
        private Defaults(Schema schema, Map<String, Object> overrides) {
            this(new HashMap<>());
            for (Schema.Setting setting : schema.settings()) {
                switch (setting) {
                    case Schema.Setting.Flag flag -> values.put(flag.key(), flag.byDefault());
                    case Schema.Setting.Count count -> values.put(count.key(), count.byDefault());
                    case Schema.Setting.Choice choice -> values.put(choice.key(), choice.byDefault());
                    case Schema.Setting.Items items -> values.put(items.key(), ItemSpec.anyOf(items.byDefault().stream()
                        .map(filter -> filter.tag() ? ItemSpec.of(TagKey.create(Registries.ITEM, filter.id()))
                            : ItemSpec.of(filter.id())).toList()));
                }
            }
            values.putAll(overrides);
        }

        @Override
        public boolean flag(String key) {
            return (Boolean) values.get(key);
        }

        @Override
        public int count(String key) {
            return (Integer) values.get(key);
        }

        @Override
        public ResourceLocation choice(String key) {
            return (ResourceLocation) values.get(key);
        }

        @Override
        public Optional<ItemSpec> items(String key) {
            return Optional.ofNullable((ItemSpec) values.get(key));
        }
    }
}
