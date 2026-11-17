package io.github.izakyl.folkways.airline;

import static io.github.izakyl.folkways.airline.AirlineTestArea.testName;

import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.charter.Charter;
import io.github.izakyl.folkways.core.front.engine.charter.Charters;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysAirlineMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_airline")
@PrefixGameTestTemplate(false)
public final class AirportGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(AirportGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void literalHelipadAndRefusals(GameTestHelper helper) {
        Pattern pattern = new Pattern();
        AirportMarkings.pad(pattern.cells, BlockPos.ZERO, 11, false, false);
        helper.assertValueEqual(pattern.scan().helipads(), List.of(new Helipad(BlockPos.ZERO, 11)), "white H");
        pattern.put(0, 0, AirportSurface.GRAY);
        helper.assertTrue(pattern.scan().helipads().isEmpty(), "crossbar required");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void straightAndBentTaxiways(GameTestHelper helper) {
        Pattern pattern = runwayAndStand();
        Airport airport = pattern.scan();
        BlockPos stand = airport.stands().getFirst().pos();
        Set<BlockPos> landing = Set.of(airport.runways().getFirst().firstThreshold());
        helper.assertTrue(airport.ground().reachableFrom(landing, 1).contains(stand), "narrow aircraft passes");
        helper.assertTrue(!airport.ground().reachableFrom(landing, 5).contains(stand), "wide aircraft cannot pass neck");
        pattern.rect(-1, -8, 1, -8, AirportSurface.OTHER);
        helper.assertTrue(has(pattern.scan(), AirportFlaw.STAND_UNREACHABLE), "pavement cut disconnects stand");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void junctionsLoopsAndPavement(GameTestHelper helper) {
        Pattern pattern = runwayAndStand();
        pattern.rect(-30, -30, 30, 20, AirportSurface.GRAY);
        AirportMarkings.runway(pattern.cells, new Runway(new BlockPos(-24, 0, -20), new BlockPos(23, 0, -20), 5));
        pattern.line(0, 0, 0, 7);
        pattern.line(-1, 8, 1, 8, AirportSurface.WHITE);
        AirportMarkings.pad(pattern.cells, new BlockPos(20, 0, 8), 11, true, false);
        Airport airport = pattern.scan();
        helper.assertValueEqual(airport.runways().size(), 1, "runway on shared pavement");
        helper.assertValueEqual(airport.helipads().size(), 1, "pad on shared pavement");
        helper.assertValueEqual(airport.stands().size(), 1, "lead-in and stop line on shared apron");
        helper.assertTrue(airport.ground().reachableFrom(Set.of(airport.runways().getFirst().firstThreshold()), 3)
            .contains(airport.stands().getFirst().pos()), "unmarked apron is taxiable");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void standsRunwaysAndHoldingPoints(GameTestHelper helper) {
        Airport airport = runwayAndStand().scan();
        helper.assertValueEqual(airport.runways().size(), 1, "marked runway");
        helper.assertValueEqual(airport.stands().size(), 1, "marked stand");
        helper.assertTrue(airport.flaws().isEmpty(), "connected facilities: " + airport.flaws());
        helper.assertValueEqual(AirportData.read(AirportData.write(airport)), airport, "raster paths persist");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void runwayEndEntrancesAndAsymmetricStands(GameTestHelper helper) {
        Pattern pattern = runwayAndStand();
        Airport airport = pattern.scan();
        helper.assertTrue(airport.ground().edges().stream().allMatch(edge -> edge.cells().stream()
            .allMatch(pattern::pavement)), "paths remain on pavement");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void helipadConnectionAndStopLineRules(GameTestHelper helper) {
        Pattern pattern = runwayAndStand();
        pattern.put(0, 7, AirportSurface.GRAY);
        helper.assertTrue(pattern.scan().stands().isEmpty(), "lead-in must reach stop line");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void overlappingStandsAndShortEntrances(GameTestHelper helper) {
        Pattern pattern = new Pattern();
        pattern.rect(-15, -15, 15, 15, AirportSurface.GRAY);
        pattern.line(-5, -8, -5, 7);
        pattern.line(5, -8, 5, 7);
        pattern.line(-6, 8, -4, 8, AirportSurface.WHITE);
        pattern.line(4, 8, 6, 8, AirportSurface.WHITE);
        Airport airport = pattern.scan();
        helper.assertValueEqual(airport.stands().size(), 2, "two lead-ins");
        helper.assertTrue(!airport.stands().getFirst().footprint().overlaps(airport.stands().get(1).footprint()),
            "neighbour stands divide clearance");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void schedulingCandidates(GameTestHelper helper) {
        Airport airport = runwayAndStand().scan().named("Test", List.of());
        AirportRequirements fits = new AirportRequirements(7, 9, 3, true);
        Airport.Candidates candidates = airport.candidates(fits);
        helper.assertTrue(candidates.helipads().isEmpty(), "flights never use a runway");
        helper.assertValueEqual(candidates.stands().size(), 1, "fitting stand reachable through the runway taxiway");
        helper.assertTrue(airport.candidates(new AirportRequirements(7, 30, 3, true)).stands().isEmpty(),
            "stand too short");
        helper.assertTrue(airport.candidates(new AirportRequirements(7, 9, 12, true)).stands().isEmpty(),
            "stand too narrow");
        Airport incomplete = airport.named("Test", List.of(
            new Airport.Problem(AirportFlaw.UNLOADED, BlockPos.ZERO, "")));
        helper.assertTrue(incomplete.candidates(fits).stands().isEmpty(), "no stale dispatch from incomplete scan");
        Pattern pad = new Pattern();
        AirportMarkings.pad(pad.cells, BlockPos.ZERO, 7, false, false);
        Airport heliport = pad.scan().named("H", List.of());
        helper.assertValueEqual(heliport.candidates(new AirportRequirements(7, 1, 1, true))
            .helipads().size(), 1, "VTOL pad fits");
        helper.assertTrue(heliport.candidates(new AirportRequirements(7, 1, 1, false)).helipads().isEmpty(),
            "an aircraft without vertical take-off cannot use a pad");
        helper.assertTrue(heliport.candidates(new AirportRequirements(9, 1, 1, true)).helipads().isEmpty(),
            "pad too small");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void registryNamesPersistenceAndBookEnrollment(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(1, 4, 1));
        BlockPos second = first.east(3);
        level.setBlockAndUpdate(first, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        level.setBlockAndUpdate(second, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        Airports.register(level, first);
        Airports.register(level, second);
        helper.assertTrue(has(Airports.at(level, first).orElseThrow(), AirportFlaw.UNNAMED), "unnamed tower");
        Airports.rename(level, first, testName(helper, "Airport test"));
        helper.assertValueEqual(Airports.byName(level, testName(helper,
            "Airport test")).orElseThrow().tower(), first, "by name");
        Airports.rename(level, second, testName(helper, "Airport test"));
        helper.assertTrue(Airports.byName(level, testName(helper, "Airport test")).isEmpty(),
            "duplicate name not guessed");
        helper.assertTrue(has(Airports.at(level, first).orElseThrow(), AirportFlaw.SHARED_NAME), "first duplicate");
        helper.assertTrue(has(Airports.at(level, second).orElseThrow(), AirportFlaw.SHARED_NAME), "second duplicate");
        Airports.rename(level, second, testName(helper, "Other airport"));
        helper.assertTrue(!has(Airports.at(level, first).orElseThrow(), AirportFlaw.SHARED_NAME),
            "rename clears conflict");
        helper.assertTrue(Airports.byName(level.getServer().getLevel(Level.NETHER), testName(helper,
            "Airport test")).isEmpty(),
            "registries belong to dimensions");
        AirportRegistry restored = AirportRegistry.load(AirportRegistry.of(level)
            .save(new CompoundTag(), level.registryAccess()));
        helper.assertValueEqual(restored.airport(level, first).name(), testName(helper, "Airport test"),
            "SavedData name");
        helper.assertTrue(has(restored.airport(level, first), AirportFlaw.UNLOADED), "geometry rebuilt after reload");
        var delegation = Enrollments.delegation(AirportColony.TOWER).orElseThrow();
        helper.assertTrue(delegation.shape() instanceof Shape.Block block && block.accepts().test(level, first),
            "tower uses existing block delegation");
        var colony = Colonies.mint(level.getServer());
        var holding = colony.hold(AirportColony.TOWER, new Claimed.Block(WorldPos.of(level, first))).orElseThrow();
        helper.assertTrue(colony.view(level).blocks(AirportColony.TOWER).contains(first), "tower in colony ledger");
        helper.assertTrue(new AirportColony().boardAt(AirportColony.ID, first, colony.view(level)).isPresent(),
            "site page");
        colony.release(holding.id(), io.github.izakyl.folkways.core.colony.api.ClaimRelease.LET_GO);
        colony.raze();
        level.removeBlock(first, false);
        helper.assertTrue(Airports.byName(level, testName(helper, "Airport test")).isEmpty(),
            "breaking tower unregisters");
        level.removeBlock(second, false);
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void sharedFacilitiesAndOversizedSurveys(GameTestHelper helper) {
        AirportRegistry registry = new AirportRegistry();
        BlockPos other = BlockPos.ZERO.east(10);
        registry.register(BlockPos.ZERO);
        registry.register(other);
        Airport geometry = runwayAndStand().scan();
        registry.cache(geometry);
        registry.cache(new Airport("", other, geometry.runways(), geometry.helipads(), geometry.stands(),
            geometry.ground(), geometry.flaws()));
        helper.assertTrue(has(registry.airport(helper.getLevel(), BlockPos.ZERO), AirportFlaw.OVERLAP),
            "shared facilities mark first tower");
        helper.assertTrue(has(registry.airport(helper.getLevel(), other), AirportFlaw.OVERLAP),
            "shared facilities mark second tower");
        registry.remove(other);
        helper.assertTrue(!has(registry.airport(helper.getLevel(), BlockPos.ZERO), AirportFlaw.OVERLAP),
            "removing tower clears overlap");
        Pattern oversized = new Pattern();
        oversized.rect(-40, -40, 40, 40, AirportSurface.GRAY);
        Airport limited = oversized.scan();
        helper.assertTrue(!has(limited, AirportFlaw.SURVEY_TOO_LARGE), "broad apron is allowed");
        helper.assertTrue(limited.ground().nodes().isEmpty(), "oversized graph is not partially accepted");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void towerUiAuthorityRecipeAndDrops(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos tower = helper.absolutePos(new BlockPos(1, 6, 1));
        level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        Airports.register(level, tower);
        var keeper = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "airport_keeper"));
        keeper.setPos(tower.getX() + 0.5, tower.getY(), tower.getZ() + 0.5);
        var intruder = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "airport_intruder"));
        intruder.setPos(keeper.position());
        AirportPage page = new AirportPage(tower);
        helper.assertTrue(page.build(keeper) != null, "LDLib2 airport page builds on server");
        helper.assertTrue(page.rename(keeper, "Before handover"), "unheld tower can be named nearby");
        var colony = Colonies.mint(level.getServer());
        Charters.keep(colony, Charter.founded("Airport UI", keeper.getUUID()));
        colony.hold(AirportColony.TOWER, new Claimed.Block(WorldPos.of(level, tower))).orElseThrow();
        try {
            helper.assertTrue(!page.rename(intruder, "Stolen"), "other players cannot rename held tower");
            CompoundTag rename = new CompoundTag();
            rename.putLong("tower", tower.asLong());
            rename.putString("before", "Before handover");
            rename.putString("name", "Keeper name");
            helper.assertTrue(page.applyRename(keeper, rename), "keeper can rename held tower");
            helper.assertTrue(!page.applyRename(keeper, rename), "stale rename does not target a newer name");
            rename.putLong("tower", tower.east().asLong());
            helper.assertTrue(!page.applyRename(keeper, rename), "rename cannot change another tower");
            helper.assertTrue(AirlineContent.AIRPORT_TOWER.get().createUI(
                AirlineContent.AIRPORT_TOWER.get().createUIHolder(keeper, tower, level.getBlockState(tower))) != null,
                "tower uses LDLib2 block UI and colony desk");
            Charters.keep(colony, Charter.founded("Revoked", UUID.randomUUID()));
            helper.assertTrue(!page.rename(keeper, "Revoked"), "authority is rechecked for every action");
            helper.assertValueEqual(Airports.at(level, tower).orElseThrow().name(), "Keeper name",
                "refusal preserves name");
            var recipe = level.getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(
                FolkwaysAirlineMod.ID, "airport_tower")).orElseThrow();
            helper.assertTrue(recipe.value().getResultItem(level.registryAccess())
                .is(AirlineContent.AIRPORT_TOWER.asItem()),
                "tower recipe loaded");
            var drops = Block.getDrops(level.getBlockState(tower), level, tower, level.getBlockEntity(tower),
                keeper, Items.IRON_PICKAXE.getDefaultInstance());
            helper.assertTrue(drops.size() == 1 && drops.getFirst().is(AirlineContent.AIRPORT_TOWER.asItem()),
                "tower loot table loaded");
        } finally {
            colony.raze();
            level.removeBlock(tower, false);
        }
        helper.succeed();
    }

    // Exclusive: asserts server-wide scanner copy work and per-tick budgets.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airport_scan", timeoutTicks = 600)
    public static void budgetedScanAndApproachObstacles(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = AirlineTestArea.of(helper).pos(0, 80 + 5, 0);
        // At this alignment the final approach cell crosses the chunk missed by the old 8-block stride.
        BlockPos origin = new BlockPos((base.getX() & -16) + 10, base.getY(), base.getZ());
        Set<ChunkPos> forced = new HashSet<>();
        loadArea(level, origin, AirportScan.VALIDATION_RADIUS, forced);
        // Include the last threshold + approach cell: an 8-block stride can miss its chunk.
        for (int x = -152; x <= 151; x++) {
            for (int z = -22; z <= -18; z++) {
                force(level, (origin.getX() + x) >> 4, (origin.getZ() + z) >> 4, forced);
            }
        }
        helper.assertTrue(forced.contains(new ChunkPos(origin.offset(151, 0, -20))),
            "fixture preloads the far approach endpoint chunk");
        Pattern pattern = runwayAndStand();
        pattern.cells.forEach((pos, material) -> level.setBlockAndUpdate(origin.offset(pos), switch (material) {
            case AirportSurface.GRAY -> Blocks.GRAY_CONCRETE.defaultBlockState();
            case AirportSurface.WHITE -> Blocks.WHITE_CONCRETE.defaultBlockState();
            default -> Blocks.YELLOW_CONCRETE.defaultBlockState();
        }));
        BlockPos tower = origin.offset(15, 12, 45);
        // Control radius is centered on the actual tower, not on the pattern's origin.
        loadArea(level, tower, AirportScan.VALIDATION_RADIUS, forced);
        level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
            .setValue(AirportTowerBlock.FACING, Direction.SOUTH));
        WorkLog work = new WorkLog(helper);
        GameTestTicks.onEachTick(helper, work::observe);
        Airports.request(level, tower);
        String airportName = testName(helper, "Budgeted " + tower.toShortString());
        Airports.rename(level, tower, airportName);
        BlockPos obstacle = origin.offset(-32, 4, -20);
        // The stone top must remain below the 1:20 clearance plane.
        BlockPos belowPlane = origin.offset(51, 1, -20);
        level.setBlockAndUpdate(obstacle, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(belowPlane, Blocks.STONE.defaultBlockState());
        helper.runAfterDelay(1, () -> helper.assertTrue(Airports.scanning(level, tower), "scan spans ticks"));
        helper.succeedWhen(() -> {
            work.observe();
            helper.assertTrue(!Airports.scanning(level, tower), "scan complete");
            helper.assertTrue(work.copiedCells > Airports.TICK_BUDGET, "fixture requires multiple copy budgets");
            helper.assertTrue(work.copyTicks >= 2, "world copying actually runs in multiple ticks");
            helper.assertTrue(Airports.byName(level, airportName).isPresent(), "registered airport");
            Airport airport = Airports.byName(level, airportName).orElseThrow();
            helper.assertTrue(airport.runways().size() == 1, "world runway: " + airport);
            helper.assertValueEqual(airport.stands().size(), 1, "world stand");
            helper.assertTrue(!has(airport, AirportFlaw.UNLOADED), "loaded scan complete: " + airport.flaws());
            helper.assertValueEqual(count(airport, AirportFlaw.APPROACH_OBSTRUCTED), 1L, "one blocked end");
            helper.assertValueEqual(airport.flaws().stream().filter(problem ->
                problem.kind() == AirportFlaw.APPROACH_OBSTRUCTED).findFirst().orElseThrow().detail(),
                "09", "blocked number");
            level.removeBlock(tower, false);
            level.removeBlock(obstacle, false);
            level.removeBlock(belowPlane, false);
            forced.forEach(chunk -> TowerTestSite.releaseChunk(level, chunk));
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rescanCursorSurvivesRegistryChanges(GameTestHelper helper) {
        AirportRegistry registry = new AirportRegistry();
        registry.register(BlockPos.ZERO.east());
        registry.register(BlockPos.ZERO.east(3));
        registry.register(BlockPos.ZERO.east(5));
        BlockPos cursor = registry.nextTower(null);
        helper.assertValueEqual(cursor, BlockPos.ZERO.east(), "first tower");
        registry.remove(cursor);
        registry.register(BlockPos.ZERO.east(2));
        registry.register(BlockPos.ZERO);
        helper.assertValueEqual(registry.nextTower(cursor), BlockPos.ZERO.east(2), "insert ahead of removed cursor");
        registry.remove(BlockPos.ZERO.east(3));
        registry.rename(BlockPos.ZERO.east(5), "Changed while enumerating");
        helper.assertValueEqual(registry.nextTower(BlockPos.ZERO.east(2)), BlockPos.ZERO.east(5),
            "removal and rename do not invalidate cursor");
        helper.assertTrue(registry.nextTower(BlockPos.ZERO.east(5)) == null, "pass ends");
        helper.assertValueEqual(registry.nextTower(null), BlockPos.ZERO, "next pass sees earlier insert");
        helper.succeed();
    }

    // Exclusive: requests a global rescan and counts the dimension-wide registry.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airport_rescan", timeoutTicks = 600)
    public static void periodicTowerChecksShareTheTickBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos relative = AirlineTestArea.of(helper).pos(0, 80 + 16, 0);
        BlockPos base = new BlockPos(relative.getX() & -16, relative.getY(), relative.getZ() & -16);
        Set<ChunkPos> forced = new HashSet<>();
        force(level, base.getX() >> 4, base.getZ() >> 4, forced);
        AirportRegistry registry = AirportRegistry.of(level);
        List<BlockPos> stale = new ArrayList<>();
        for (int index = 0; index < Airports.TICK_BUDGET * 2 + 64; index++) {
            BlockPos pos = base.offset(index & 15, index >> 8, (index >> 4) & 15);
            registry.register(pos);
            stale.add(pos);
        }
        int before = registry.towers().size();
        WorkLog work = new WorkLog(helper);
        GameTestTicks.onEachTick(helper, work::observe);
        Airports.requestRescan(level.getServer());
        helper.assertValueEqual(registry.towers().size(), before, "request does not eagerly enumerate towers");
        helper.runAfterDelay(1, () -> {
            work.observe();
            helper.assertTrue(before - registry.towers().size() <= Airports.TICK_BUDGET,
                "one tick cannot validate and remove every stale tower");
        });
        helper.succeedWhen(() -> {
            work.observe();
            helper.assertTrue(stale.stream().noneMatch(registry::contains), "all stale towers eventually checked");
            helper.assertTrue(work.rescanTowers >= stale.size(), "all tower checks included in budget counters");
            helper.assertTrue(work.rescanTicks >= 3, "tower enumeration spans several budgets");
            forced.forEach(chunk -> TowerTestSite.releaseChunk(level, chunk));
        });
    }

    // Exclusive: samples server-wide scanner work for the copied validation border.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airport_boundary", timeoutTicks = 600)
    public static void runwayAtRadiusEdgeUsesCopiedValidationBorder(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = AirlineTestArea.of(helper).pos(0, 80 + 5, 0);
        BlockPos tower = origin.above(44);
        Set<ChunkPos> forced = new HashSet<>();
        loadArea(level, tower, AirportScan.VALIDATION_RADIUS, forced);
        Pattern pattern = new Pattern();
        for (int centerZ : new int[] {-381, -400}) {
            AirportMarkings.runway(pattern.cells, new Runway(new BlockPos(-24, 0, centerZ),
                new BlockPos(23, 0, centerZ), 5));
        }
        pattern.cells.forEach((pos, material) -> level.setBlockAndUpdate(origin.offset(pos),
            (material == AirportSurface.GRAY ? Blocks.GRAY_CONCRETE : Blocks.WHITE_CONCRETE).defaultBlockState()));
        for (int x = -152; x <= 151; x++) {
            for (int z = -383; z <= -379; z++) {
                force(level, (origin.getX() + x) >> 4, (origin.getZ() + z) >> 4, forced);
            }
        }
        helper.assertTrue(AirportScan.inside(tower, origin.offset(-24, 0, -383)), "runway corner inside radius");
        BlockPos probe = origin.offset(-24, 0, -384);
        helper.assertTrue(!AirportScan.inside(tower, probe), "side probe outside facility radius");
        helper.assertTrue(Runways.at(level, origin.offset(0, 0, -381)).isPresent(), "world runway is valid");
        level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState()
            .setValue(AirportTowerBlock.FACING, Direction.SOUTH));
        Airports.request(level, tower);
        helper.startSequence().thenWaitUntil(() -> {
            helper.assertTrue(!Airports.scanning(level, tower), "initial edge survey complete");
            Airport airport = Airports.at(level, tower).orElseThrow();
            helper.assertValueEqual(airport.runways().size(), 1, "edge runway accepted; outside runway excluded");
            helper.assertValueEqual(airport.runways().getFirst().firstThreshold(), origin.offset(-24, 0, -381),
                "only the wholly contained runway is governed");
            helper.assertTrue(!has(airport, AirportFlaw.UNLOADED), "validation border was copied from loaded chunks");
        }).thenExecute(() -> {
            level.setBlockAndUpdate(probe, Blocks.GRAY_CONCRETE.defaultBlockState());
            Airports.request(level, tower);
        }).thenWaitUntil(() -> {
            helper.assertTrue(!Airports.scanning(level, tower), "blocked edge survey complete");
            Airport airport = Airports.at(level, tower).orElseThrow();
            helper.assertValueEqual(airport.runways().size(), 1, "adjoining pavement preserves the runway");
        }).thenExecute(() -> {
            level.removeBlock(tower, false);
            level.removeBlock(probe, false);
            forced.forEach(chunk -> TowerTestSite.releaseChunk(level, chunk));
        }).thenSucceed();
    }

    // Exclusive: asserts actual chunk absence and absence of scanner force-loading.
    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "airport_unloaded", timeoutTicks = 300)
    public static void unloadedChunksAreNotForced(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos tower = AirlineTestArea.of(helper).unloadedPos(0, 85, 0);
        level.setChunkForced(tower.getX() >> 4, tower.getZ() >> 4, true);
        level.getChunk(tower.getX() >> 4, tower.getZ() >> 4);
        BlockPos probe = tower.east(112);
        helper.assertTrue(!level.hasChunkAt(probe), "probe starts unloaded");
        level.setBlockAndUpdate(tower, AirlineContent.AIRPORT_TOWER.get().defaultBlockState());
        Airports.request(level, tower);
        helper.succeedWhen(() -> {
            helper.assertTrue(!Airports.scanning(level, tower), "scan complete");
            helper.assertTrue(has(Airports.at(level, tower).orElseThrow(), AirportFlaw.UNLOADED), "unloaded flaw");
            helper.assertTrue(!level.hasChunkAt(probe), "scanner did not load probe");
            level.removeBlock(tower, false);
            level.setChunkForced(tower.getX() >> 4, tower.getZ() >> 4, false);
        });
    }

    private static void loadArea(ServerLevel level, BlockPos center, int radius, Set<ChunkPos> forced) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) {
                force(level, x, z, forced);
            }
        }
    }

    private static void force(ServerLevel level, int x, int z, Set<ChunkPos> forced) {
        forced.add(new ChunkPos(x, z));
        level.setChunkForced(x, z, true);
        level.getChunk(x, z);
    }

    private static Pattern runwayAndStand() {
        Pattern pattern = new Pattern();
        AirportMarkings.runway(pattern.cells, new Runway(new BlockPos(-24, 0, -20), new BlockPos(23, 0, -20), 5));
        pattern.rect(-1, -17, 1, -1, AirportSurface.GRAY);
        pattern.rect(-5, 0, 5, 11, AirportSurface.GRAY);
        pattern.line(0, 0, 0, 7);
        pattern.line(-1, 8, 1, 8, AirportSurface.WHITE);
        return pattern;
    }

    private static boolean has(Airport airport, AirportFlaw flaw) {
        return count(airport, flaw) > 0;
    }

    private static long count(Airport airport, AirportFlaw flaw) {
        return airport.flaws().stream().filter(problem -> problem.kind() == flaw).count();
    }

    private static final class WorkLog {
        private final GameTestHelper helper;
        private final int started;
        private int observed = -1;
        private int copiedCells;
        private int copyTicks;
        private int rescanTowers;
        private int rescanTicks;

        private WorkLog(GameTestHelper helper) {
            this.helper = helper;
            started = helper.getLevel().getServer().getTickCount();
        }

        private void observe() {
            Airports.TickWork work = Airports.lastTickWork(helper.getLevel().getServer());
            if (work.tick() < started || work.tick() == observed) {
                return;
            }
            if (observed >= started) {
                helper.assertValueEqual(work.tick(), observed + 1, "every survey tick is observed");
            }
            observed = work.tick();
            helper.assertTrue(work.steps() <= Airports.TICK_BUDGET, "total work stays inside the tick budget");
            helper.assertTrue(work.copySteps() <= work.steps(), "copy steps counted independently of scheduler steps");
            helper.assertTrue(work.copiedCells() <= work.copySteps(), "each copied cell consumes a copy step");
            helper.assertTrue(work.rescanTowers() + work.copySteps() <= work.steps(),
                "tower enumeration and copying share one budget");
            copiedCells += work.copiedCells();
            rescanTowers += work.rescanTowers();
            if (work.copiedCells() > 0) {
                copyTicks++;
            }
            if (work.rescanTowers() > 0) {
                rescanTicks++;
            }
        }
    }

    private static final class Pattern implements AirportSurface {
        private final Map<BlockPos, Integer> cells = new HashMap<>();

        private void put(int x, int z, int material) {
            BlockPos pos = new BlockPos(x, 0, z);
            if (material == OTHER) {
                cells.remove(pos);
            } else {
                cells.put(pos, material);
            }
        }

        private void rect(int x1, int z1, int x2, int z2, int material) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    put(x, z, material);
                }
            }
        }

        private void line(int x1, int z1, int x2, int z2) {
            line(x1, z1, x2, z2, YELLOW);
        }

        private void line(int x1, int z1, int x2, int z2, int material) {
            rect(x1, z1, x2, z2, material);
        }

        private void rows(int x, int z, String... rows) {
            for (int row = 0; row < rows.length; row++) {
                for (int column = 0; column < rows[row].length(); column++) {
                    put(x + column, z + row, switch (rows[row].charAt(column)) {
                        case 'W' -> WHITE;
                        case 'Y' -> YELLOW;
                        default -> GRAY;
                    });
                }
            }
        }

        private Airport scan() {
            return new AirportSurvey(this, cells.keySet(), BlockPos.ZERO).scan();
        }

        @Override
        public int material(BlockPos pos) {
            return cells.getOrDefault(pos, OTHER);
        }

        @Override
        public boolean loaded(BlockPos pos) {
            return AirportScan.inside(BlockPos.ZERO, pos);
        }
    }
}
