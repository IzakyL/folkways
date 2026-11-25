package io.github.izakyl.folkways.airline;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
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
public final class TowerGameTests {
    private static final Logger LOGGER = LoggerFactory.getLogger(TowerGameTests.class);

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TowerGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void heightSectorAndOcclusionRules(GameTestHelper helper) {
        var tower = new TowerCoverage.Tower(new BlockPos(0, 44, 180), Direction.SOUTH, null, false);
        var area = new Airport.Rectangle(-256, -2, 255, 2, 0);
        helper.assertTrue(TowerCoverage.inspect(area, List.of(tower), pos -> false).covered(),
            "512-block footprint is fully sampled inside the tall tower sector");
        var low = new TowerCoverage.Tower(tower.pos().below(43), Direction.SOUTH, null, false);
        helper.assertTrue(!TowerCoverage.inspect(area, List.of(low), pos -> false).covered(), "low tower refuses");
        var backwards = new TowerCoverage.Tower(tower.pos(), Direction.NORTH, null, false);
        helper.assertValueEqual(TowerCoverage.inspect(area, List.of(backwards), pos -> false).reason(), "sector",
            "operator side of tower is excluded");
        helper.assertValueEqual(tower.radius(44), 0, "same height is never covered");
        helper.assertValueEqual(tower.radius(-100), 384, "radius is capped");
        for (var state : List.of(Blocks.GLASS.defaultBlockState(), Blocks.OAK_LEAVES.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState())) {
            helper.assertTrue(!TowerCoverage.blocksSight(state, EmptyBlockGetter.INSTANCE, BlockPos.ZERO),
                "glass, leaves and fences do not occlude: " + state);
        }
        helper.assertTrue(TowerCoverage.blocksSight(Blocks.STONE.defaultBlockState(), EmptyBlockGetter.INSTANCE,
            BlockPos.ZERO), "full stone occludes");
        var point = new Airport.Rectangle(0, 0, 0, 0, 0);
        BlockPos blocker = TowerCoverage.ray(tower.pos(), BlockPos.ZERO).get(12);
        helper.assertValueEqual(TowerCoverage.inspect(point, List.of(tower), blocker::equals).reason(), "obstructed",
            "a copied full block on the exact ray hides the point");
        helper.assertTrue(TowerCoverage.samples(area).contains(new BlockPos(255, 0, 2)), "far corner included");
        helper.assertTrue(TowerCoverage.samples(area).size() < 150, "boundary sampling avoids every pavement cell");
        helper.succeed();
    }

    // Exclusive: measures server-wide scanner work and tick budgets for the 512-block runway.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "tower_maximum", timeoutTicks = 2400)
    public static void maximumRangeScansReal512RunwayWithinTickBudget(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos tower = origin.offset(0, 44, 180);
        site.load(tower);
        site.runway(origin.west(256), Direction.EAST, 512, 5);
        site.tower(tower, Direction.NORTH, null, "Maximum " + tower.asLong());
        int began = site.level.getServer().getTickCount();
        long started = System.nanoTime();
        long[] work = new long[3];
        GameTestTicks.onEachTick(helper, () -> {
            var tick = Airports.lastTickWork(site.level.getServer());
            helper.assertTrue(tick.steps() <= Airports.TICK_BUDGET, "maximum scan respects per-tick work cap");
            work[0] += tick.nanos();
            work[1] = Math.max(work[1], tick.nanos());
            work[2] += tick.copiedCells();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!Airports.scanning(site.level, tower), "maximum scan completes");
            Airport airport = Airports.at(site.level, tower).orElseThrow();
            helper.assertValueEqual(airport.runways().size(), 1, "real 512-block runway is fully identified");
            helper.assertValueEqual(airport.runways().getFirst().length(), 512, "full inclusive length retained");
            helper.assertTrue(airport.flaws().isEmpty(), "complete world survey: " + airport.flaws());
            helper.assertTrue(airport.coverage().stream().allMatch(TowerCoverage.Facility::covered),
                "every boundary sample passes range, sector and copied sight checks");
            helper.assertValueEqual(AirportData.read(AirportData.write(airport)), airport,
                "tower and visibility metadata survive UI sync");
            LOGGER.info("TOWER_MAXIMUM ticks={} wallMs={} tickWorkMs={} maxTickMicros={} copiedCells={}",
                site.level.getServer().getTickCount() - began, (System.nanoTime() - started) / 1_000_000.0,
                work[0] / 1_000_000.0, work[1] / 1000.0, work[2]);
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_0", timeoutTicks = 1200)
    public static void realStoneObstructsAndGlassRestoresRunway(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos tower = origin.offset(0, 20, 40);
        site.tower(tower, Direction.NORTH, null, "Sight");
        site.runway(origin, Direction.EAST, 48, 5);
        helper.assertValueEqual(site.scan(tower).runways().size(), 1, "unobstructed world runway");
        BlockPos blocker = TowerCoverage.ray(tower, origin.north(2)).get(15);
        site.set(blocker, Blocks.STONE.defaultBlockState());
        Airport hidden = site.scan(tower);
        helper.assertTrue(hidden.runways().isEmpty(), "real stone hides a required boundary sample");
        helper.assertTrue(hidden.coverage().stream().anyMatch(facility -> facility.reason().equals("obstructed")),
            "UI identifies the sight obstruction");
        site.set(blocker, Blocks.GLASS.defaultBlockState());
        helper.assertValueEqual(site.scan(tower).runways().size(), 1, "real glass transmits the same sight ray");
        site.set(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
            .setValue(AirportTowerBlock.FACING, Direction.NORTH));
        helper.assertTrue(site.scan(tower).runways().isEmpty(), "real operator-facing tower refuses the runway");
        site.set(tower, Blocks.AIR.defaultBlockState());
        BlockPos low = origin.offset(0, 1, 40);
        site.tower(low, Direction.NORTH, null, "Low");
        helper.assertTrue(site.scan(low).runways().isEmpty(), "low world tower cannot cover the runway");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_0", timeoutTicks = 1800)
    public static void lowTowersShareRunwayAndMergeNames(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        var colony = site.colony();
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos left = origin.offset(-130, 24, 110);
        BlockPos right = origin.offset(130, 24, 110);
        site.tower(left, Direction.NORTH, colony, "Left");
        site.tower(right, Direction.NORTH, colony, "Right");
        site.runway(origin.west(256), Direction.EAST, 512, 5);
        Airport airport = site.scan(left);
        helper.assertValueEqual(airport.runways().size(), 1, "two short-range towers jointly see the real runway");
        helper.assertValueEqual(airport.towers().size(), 2, "shared sampled facility merges both colony towers");
        helper.assertTrue(airport.coverage().stream().noneMatch(facility -> facility.samples().stream()
            .allMatch(sample -> sample.towers().contains(left))), "left alone does not cover the runway");
        helper.assertTrue(Airports.at(site.level, left).orElseThrow().flaws().stream()
            .anyMatch(problem -> problem.kind() == AirportFlaw.NAME_MISMATCH), "different merged names are a flaw");
        Airports.rename(site.level, right, site.name("Union"));
        helper.assertValueEqual(Airports.byName(site.level, site.name("Union")).orElseThrow().towers().size(), 2,
            "one exact name addresses the merged airport");
        helper.assertValueEqual(Airports.at(site.level, left).orElseThrow().name(), site.name("Union"),
            "rename covers airport");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_1", timeoutTicks = 1800)
    public static void mergedRenameRechecksCurrentColony(GameTestHelper helper) {
        mergedRenameFixture(helper, false);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_1", timeoutTicks = 1800)
    public static void mergedRenameCannotResurrectRemovedTower(GameTestHelper helper) {
        mergedRenameFixture(helper, true);
    }

    private static void mergedRenameFixture(GameTestHelper helper, boolean remove) {
        var site = new TowerTestSite(helper);
        var colony = site.colony();
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos left = origin.offset(-130, 24, 110);
        BlockPos right = origin.offset(130, 24, 110);
        site.tower(left, Direction.NORTH, colony, "Left");
        site.tower(right, Direction.NORTH, colony, "Right");
        site.runway(origin.west(256), Direction.EAST, 512, 5);
        helper.assertValueEqual(site.scan(left).towers().size(), 2, "real survey merges the two colony towers");
        if (remove) {
            site.set(right, Blocks.AIR.defaultBlockState());
            helper.assertTrue(Airports.at(site.level, right).isEmpty(), "real tower removal unregisters its address");
        } else {
            var holding = colony.claimAt(io.github.izakyl.folkways.core.world.api.WorldPos.of(site.level, right))
                .orElseThrow();
            colony.release(holding.id(), io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
            site.colony().hold(AirportColony.TOWER, new io.github.izakyl.folkways.core.colony.api.Claimed.Block(
                io.github.izakyl.folkways.core.world.api.WorldPos.of(site.level, right))).orElseThrow();
        }
        Airports.rename(site.level, left, site.name("Renamed"));
        helper.assertValueEqual(Airports.at(site.level, left).orElseThrow().name(), site.name("Renamed"),
            "selected tower renames");
        if (remove) {
            helper.assertTrue(Airports.at(site.level, right).isEmpty(),
                "merged rename cannot re-register demolished tower");
        } else {
            helper.assertValueEqual(Airports.at(site.level, right).orElseThrow().name(), site.name("Right"),
                "merged rename cannot rename a tower now owned by another colony");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_2", timeoutTicks = 1800)
    public static void blockChangesUseConstantSpatialWork(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos base = site.pos(0, 100, 0);
        var registry = AirportRegistry.of(site.level);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 8; z++) {
                BlockPos tower = base.offset(x * 2, 0, z * 2);
                site.set(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
                Airports.register(site.level, tower);
            }
        }
        var scan = new AirportScan(site.level, base);
        long before = registry.changeVisits();
        BlockPos changed = base.offset(1, -1, 1);
        site.set(changed, Blocks.STONE.defaultBlockState());
        helper.assertTrue(registry.changeVisits() - before <= 1, "one notification per real block change");
        helper.assertTrue(scan.unchanged(), "mining ordinary terrain does not restart the scan");
        site.set(changed, Blocks.WHITE_CONCRETE.defaultBlockState());
        helper.assertTrue(!scan.unchanged(), "surface material change restarts the scan");
        helper.assertTrue(registry.pending(base), "changed surface remains unavailable until resurveyed");
        helper.assertTrue(Airports.scanning(site.level, base), "matching change immediately queues a scan");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_2", timeoutTicks = 1200)
    public static void differentColoniesAndUnownedTowersNeverMerge(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos first = origin.offset(-25, 20, 100);
        BlockPos second = origin.offset(25, 20, 100);
        site.tower(first, Direction.NORTH, site.colony(), "One");
        site.tower(second, Direction.NORTH, site.colony(), "Two");
        site.runway(origin.west(24), Direction.EAST, 48, 5);
        site.scan(first);
        site.scan(second);
        for (BlockPos tower : List.of(first, second)) {
            Airport airport = Airports.at(site.level, tower).orElseThrow();
            helper.assertValueEqual(airport.towers().size(), 1, "different colonies stay separate");
            helper.assertTrue(airport.flaws().stream().anyMatch(problem -> problem.kind() == AirportFlaw.OVERLAP),
                "shared facility is a defect for both airports");
        }
        helper.assertValueEqual(TowerDuty.group(site.level, first).size(), 1, "colony membership bounds scan union");
        var third = second.east(5);
        site.tower(third, Direction.NORTH, null, "Unowned");
        helper.assertValueEqual(TowerDuty.group(site.level, third).size(), 1, "unowned tower remains independent");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_3", timeoutTicks = 1800)
    public static void taxiwayMergesTowersAndSplitReportsDuplicateNames(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        var colony = site.colony();
        BlockPos base = site.pos(0, 0, 0);
        BlockPos origin = new BlockPos(base.getX(), 80, base.getZ());
        BlockPos left = origin.offset(-80, 10, 60);
        BlockPos right = origin.offset(80, 10, 60);
        site.tower(left, Direction.NORTH, colony, "Taxi union");
        site.tower(right, Direction.NORTH, colony, "Taxi union");
        site.pad(origin.west(80));
        site.pad(origin.east(80));
        for (int x = -76; x <= 76; x++) {
            for (int z = -1; z <= 1; z++) {
                site.set(origin.offset(x, 0, z),
                    (z == 0 ? Blocks.YELLOW_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
            }
        }
        Airport joined = site.scan(left);
        helper.assertValueEqual(joined.towers().size(), 2, "covered taxiway connects separate facilities");
        helper.assertValueEqual(joined.helipads().size(), 2, "merged airport contains both real pads");
        helper.assertTrue(joined.flaws().isEmpty(), "connected sampled taxiway: " + joined.flaws());
        for (int z = -1; z <= 1; z++) {
            site.set(origin.offset(0, 0, z), Blocks.AIR.defaultBlockState());
        }
        site.scan(left);
        site.scan(right);
        for (BlockPos tower : List.of(left, right)) {
            Airport split = Airports.at(site.level, tower).orElseThrow();
            helper.assertValueEqual(split.towers().size(), 1, "broken connection splits towers");
            helper.assertValueEqual(split.helipads().size(), 1, "split topology contains only its own facility");
            helper.assertTrue(split.flaws().stream().anyMatch(problem -> problem.kind() == AirportFlaw.SHARED_NAME),
                "split airports retain names and report duplication");
        }
        helper.assertTrue(Airports.byName(site.level, site.name("Taxi union")).isEmpty(),
            "ambiguous name is not guessed");
        helper.succeed();
    }

    // Exclusive: waits for the whole survey region and tower chunk to unload.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "tower_unloaded_cache", timeoutTicks = 1800)
    public static void realUnloadedRescanRetainsFacilityWithoutForcingChunks(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos origin = site.area.unloadedPos(0, 80, 0);
        BlockPos tower = origin.offset(0, 20, 80);
        site.tower(tower, Direction.NORTH, null, "Loaded cache");
        site.pad(origin);
        helper.assertValueEqual(site.scan(tower).helipads().size(), 1, "real complete initial survey");
        site.unloadExcept(tower);
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(!site.level.hasChunkAt(origin),
            "facility chunk really unloads")).thenExecute(() -> Airports.request(site.level, tower))
            .thenWaitUntil(() -> helper.assertTrue(!Airports.scanning(site.level, tower), "incomplete rescan finishes"))
            .thenExecute(() -> {
                helper.assertTrue(!site.level.hasChunkAt(origin), "rescan never forces the facility chunk");
                Airport cached = Airports.at(site.level, tower).orElseThrow();
                helper.assertValueEqual(cached.helipads().size(), 1, "last complete world facility remains cached");
                helper.assertTrue(cached.flaws().stream().noneMatch(problem -> problem.kind() == AirportFlaw.UNLOADED),
                    "unloaded chunks alone do not invalidate a completed survey");
                site.unloadAll();
            }).thenWaitUntil(() -> helper.assertTrue(!site.level.hasChunkAt(tower), "tower chunk also unloads"))
            .thenExecute(() -> {
                helper.assertTrue(Airports.available(site.level, tower), "cached tower remains a known airport");
                helper.assertTrue(!TowerDuty.staffed(site.level, tower), "unloaded duty station is unstaffed");
                helper.assertValueEqual(Airports.at(site.level, tower).orElseThrow().helipads().size(), 1,
                    "cached landing geometry survives unloading the tower itself");
                helper.assertTrue(!site.level.hasChunkAt(tower), "airport lookup never forces a tower chunk");
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_3", timeoutTicks = 1200)
    public static void colonyLaborSeatsResidentAndPlayerDoesNotCount(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        var colony = site.colony();
        BlockPos tower = site.pos(0, 92, 0);
        site.tower(tower, Direction.NORTH, colony, "Duty");
        var resident = site.staff(tower, colony);
        helper.assertTrue(!TowerDuty.staffed(site.level, tower), "offered labor is not yet staffed");
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(TowerDuty.staffed(site.level, tower),
            "colony labor moves the resident into the actual Create seat")).thenExecute(() -> {
                resident.stopRiding();
                var seat = TowerDuty.seat(site.level, tower);
                var player = helper.makeMockPlayer(GameType.SURVIVAL);
                SeatBlock.sitDown(site.level, seat, player);
                helper.assertTrue(player.isPassenger(), "player really occupies the duty seat");
                helper.assertTrue(!TowerDuty.staffed(site.level, tower), "seated player is never a controller");
                player.stopRiding();
                player.discard();
            }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedRescanRetainsCompleteCacheAndChangesInvalidate(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        var level = helper.getLevel();
        BlockPos tower = site.pos(0, 85, 0);
        level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        Airports.register(level, tower);
        Airports.rename(level, tower, site.name("Cache " + tower.asLong()));
        var registry = AirportRegistry.of(level);
        var pad = new Helipad(tower.north(20).below(4), 7);
        Airport complete = new Airport("", tower, List.of(), List.of(pad), List.of(), GroundGraph.empty(), List.of());
        registry.cache(complete);
        Airport partial = new Airport("", tower, List.of(), List.of(), List.of(), GroundGraph.empty(),
            List.of(new Airport.Problem(AirportFlaw.UNLOADED, tower, "")));
        registry.cache(partial);
        helper.assertValueEqual(Airports.at(level, tower).orElseThrow().helipads(), List.of(pad),
            "unloaded rescan retains the last complete geometry");
        var needs = new AirportRequirements(7, 0, 0, true);
        helper.assertValueEqual(Airports.at(level, tower).orElseThrow().candidates(needs).helipads().size(), 1,
            "cached complete facility remains a candidate");
        new AirportScan(level, tower);
        level.setBlockAndUpdate(pad.center(), Blocks.WHITE_CONCRETE.defaultBlockState());
        helper.assertTrue(registry.pending(tower), "actual block mutation invalidates related cache");
        helper.assertTrue(Airports.at(level, tower).orElseThrow().candidates(needs).helipads().isEmpty(),
            "invalidated geometry cannot grant flight clearance");
        registry.cache(partial);
        helper.assertTrue(registry.pending(tower), "incomplete scan cannot clear invalidation");
        level.removeBlock(pad.center().above(), false);
        level.removeBlock(tower, false);
        helper.succeed();
    }
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_2", timeoutTicks = 1800)
    public static void surveyDependenciesIgnoreFarmingAndRescanRunwayAndApproach(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        BlockPos origin = site.pos(0, 85, 0);
        BlockPos tower = origin.offset(24, 24, 80);
        site.tower(tower, Direction.NORTH, null, "Dependencies");
        site.runway(origin, Direction.EAST, 48, 5);
        helper.assertValueEqual(site.scan(tower).runways().size(), 1, "initial runway");
        Airports.cancelSurvey(site.level, tower);
        var registry = AirportRegistry.of(site.level);
        site.set(origin.south(20), Blocks.WHEAT.defaultBlockState());
        site.set(origin.south(21).below(), Blocks.AIR.defaultBlockState());
        helper.assertTrue(!registry.pending(tower), "farming and mining outside dependencies keep clearance");
        BlockPos pillar = origin.west(10).above(8);
        site.set(pillar, Blocks.STONE.defaultBlockState());
        helper.assertTrue(registry.pending(tower), "approach pillar immediately invalidates clearance");
        helper.assertTrue(Airports.scanning(site.level, tower), "rescan is queued immediately");
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(!Airports.scanning(site.level, tower), "prompt approach rescan completes");
            helper.assertTrue(Airports.at(site.level, tower).orElseThrow().flaws().stream()
                .anyMatch(problem -> problem.kind() == AirportFlaw.APPROACH_OBSTRUCTED), "pillar obstructs approach");
        }).thenExecute(() -> {
            site.set(pillar, Blocks.AIR.defaultBlockState());
            site.set(origin.offset(24, 0, 2), Blocks.AIR.defaultBlockState());
            helper.assertTrue(registry.pending(tower), "runway damage invalidates clearance");
        }).thenWaitUntil(() -> {
            helper.assertTrue(!Airports.scanning(site.level, tower), "runway rescan completes");
            Airport airport = Airports.at(site.level, tower).orElseThrow();
            helper.assertTrue(airport.runways().isEmpty(), "damaged edge fails runway validation");
        }).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void shortEdgesAndPavementSurviveFrameOffsets(GameTestHelper helper) {
        BlockPos tower = helper.absolutePos(new BlockPos(1, 5, 1));
        var edge = new GroundGraph.Edge(tower, tower.east(), 1, List.of(tower), 3);
        var empty = new GroundGraph.Edge(tower.east(), tower.east(2), 0, List.of(), 2);
        var ground = new GroundGraph(List.of(), List.of(edge, empty), List.of(), true, List.of(tower, tower.east()));
        var airport = new Airport("Offset", tower, List.of(), List.of(), List.of(), ground, List.of());
        var tag = AirportData.write(airport);
        helper.assertValueEqual(AirportData.read(tag), airport, "short and empty edge paths round-trip");
        BlockPos offset = new BlockPos(300, 20, -400);
        var shifted = AirportData.read(AirportData.offset(tag, offset));
        helper.assertValueEqual(shifted.ground().pavement(), List.of(tower.offset(offset), tower.east().offset(offset)),
            "pavement moves with the frame");
        helper.assertValueEqual(AirportData.offset(AirportData.write(shifted), BlockPos.ZERO.subtract(offset)), tag,
            "frame geometry save/restore is reversible");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void neighboringApproachFlawsStayOutsideJurisdiction(GameTestHelper helper) {
        BlockPos tower = new BlockPos(0, 24, 80);
        var own = new Runway(new BlockPos(-24, 0, 0), new BlockPos(23, 0, 0), 5);
        var other = new Runway(new BlockPos(276, 0, 0), new BlockPos(323, 0, 0), 5);
        var raw = new Airport("", tower, List.of(own, other), List.of(), List.of(), GroundGraph.empty(), List.of(
            new Airport.Problem(AirportFlaw.APPROACH_OBSTRUCTED, other.firstThreshold(), "09"),
            new Airport.Problem(AirportFlaw.UNLOADED, other.secondThreshold(), ""),
            new Airport.Problem(AirportFlaw.SURVEY_TOO_LARGE, other.firstThreshold(), "")));
        var selected = AirportJurisdiction.select(raw,
            List.of(new TowerCoverage.Tower(tower, Direction.SOUTH, null, false)), pos -> false, tower);
        helper.assertValueEqual(selected.runways(), List.of(own), "tower owns only its visible runway");
        helper.assertTrue(selected.flaws().isEmpty(), "neighbor's flaws cannot disable this airport");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void restoredFrameTowerKeepsAddressAfterUnload(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos tower = AirlineTestArea.of(helper).pos(0, 90, 0);
        var frame = io.github.izakyl.folkways.core.testing.TestFrame.stored(level, tower);
        Runnable uninstall = frame.install(true);
        try {
            var registry = new AirportRegistry();
            registry.restoreFrames(level);
            registry.register(tower);
            registry.rename(tower, "Framed");
            var saved = registry.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
            var restored = AirportRegistry.load(saved);
            restored.restoreFrames(level);
            uninstall.run();
            var unloaded = restored.save(new net.minecraft.nbt.CompoundTag(), level.registryAccess());
            helper.assertValueEqual(unloaded.getList("towers", net.minecraft.nbt.Tag.TAG_COMPOUND),
                saved.getList("towers", net.minecraft.nbt.Tag.TAG_COMPOUND), "unloaded frame retains stable tower address");
            helper.assertValueEqual(AirportRegistry.load(unloaded).save(new net.minecraft.nbt.CompoundTag(),
                level.registryAccess()), unloaded, "unresolved frame entry survives another save/restore");
        } finally {
            uninstall.run();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airline_parallel_towers_3", timeoutTicks = 1200)
    public static void towerDutyWithdrawsOnUnloadAndRemoval(GameTestHelper helper) {
        var site = new TowerTestSite(helper);
        var colony = site.colony();
        BlockPos tower = site.pos(0, 92, 0);
        site.tower(tower, Direction.NORTH, colony, "Lifecycle");
        site.staff(tower, colony);
        var entity = (AirportTower) site.level.getBlockEntity(tower);
        TowerDuty.reconcile(entity, site.level);
        var work = entity.dutyWork;
        helper.assertTrue(work != null, "duty is submitted");
        TowerDuty.reconcile(entity, site.level);
        helper.assertValueEqual(entity.dutyWork, work, "reconcile never duplicates active Watch");
        entity.onChunkUnloaded();
        helper.assertTrue(entity.dutyWork == null && entity.dutyColony == null, "unload clears withdrawn work");
        TowerDuty.reconcile(entity, site.level);
        helper.assertTrue(entity.dutyWork != null && !entity.dutyWork.equals(work), "reload submits a fresh Watch");
        site.set(tower, Blocks.AIR.defaultBlockState());
        helper.assertTrue(entity.dutyWork == null && entity.dutyColony == null, "removal withdraws fresh Watch");
        helper.succeed();
    }

}
