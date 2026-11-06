package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MineGameTests {
    private static final Hint MARK = new Hint.Zone(new BlockPos(0, 65, 0), new BlockPos(2, 65, 2));

    private MineGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MineGameTests.class);
    }

    private static Pattern mine() {
        return DraftKit.shipped("mine");
    }

    private static DraftKit.Filled settings(Pattern pattern) {
        DraftKit.Filled settings = DraftKit.Filled.defaulting(pattern.knobs()).flag("chase", false);
        for (String direction : new String[] {"east", "south", "west", "north"}) {
            settings.count("main_" + direction, 18).count("branch_" + direction, 28);
        }
        return settings;
    }

    private static Map<BlockPos, BlockState> cells(GameTestHelper helper, Pattern pattern, DraftKit.Filled settings,
            DraftKit.Ground ground) {
        Drawn drawn = pattern.drawOn(new Commission(MARK, settings, ground, Direction.NORTH, 7L));
        if (!(drawn instanceof Drawn.Ready ready)) {
            helper.fail("mine refused: " + drawn);
            throw new AssertionError(drawn);
        }
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (Draft.Cell cell : ready.draft().cells()) {
            cells.put(ready.corner().offset(cell.offset()), cell.state());
        }
        return cells;
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oneDraftReachesFloorAndAllDirections(GameTestHelper helper) {
        Pattern pattern = mine();
        Map<BlockPos, BlockState> cells = cells(helper, pattern, settings(pattern),
            new DraftKit.Ground(64).floorAt(-64));
        helper.assertTrue(cells.values().stream().anyMatch(state -> state.is(Blocks.SPRUCE_FENCE_GATE)), "head gate");
        helper.assertTrue(cells.keySet().stream().anyMatch(at -> at.getY() == 16), "stair reaches floor");
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos end = new BlockPos(1, 17, 1).relative(direction, 26);
            helper.assertTrue(cells.getOrDefault(end, Blocks.STONE.defaultBlockState()).isAir(), "main reaches " + end);
            BlockPos branch = new BlockPos(1, 17, 1).relative(direction, 13)
                .relative(direction.getClockWise(), 29);
            helper.assertTrue(cells.getOrDefault(branch, Blocks.STONE.defaultBlockState()).isAir(),
                "branch reaches " + branch);
        }
        BlockPos crossing = new BlockPos(14, 17, 14);
        helper.assertTrue(cells.getOrDefault(crossing, Blocks.STONE.defaultBlockState()).isAir(), "crossing is open");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void openCaveDoesNotEndTheDraft(GameTestHelper helper) {
        Pattern pattern = mine();
        DraftKit.Ground ground = new DraftKit.Ground(64).floorAt(-64);
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                for (int y = 17; y <= 19; y++) {
                    ground.place(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }
        Map<BlockPos, BlockState> drawn = cells(helper, pattern, settings(pattern), ground);
        helper.assertTrue(drawn.values().stream().anyMatch(state -> state.is(Blocks.SPRUCE_FENCE_GATE)), "head drawn");
        helper.assertTrue(drawn.keySet().stream().anyMatch(at -> at.getY() == 16), "stair reaches cave");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void oreBesideAPlannedPassageIsChased(GameTestHelper helper) {
        Pattern pattern = mine();
        DraftKit.Filled settings = settings(pattern);
        Map<BlockPos, BlockState> plain = cells(helper, pattern, settings, new DraftKit.Ground(64).floorAt(-64));
        BlockPos ore = null;
        for (BlockPos at : plain.keySet()) {
            if (at.getY() != 17 || !plain.get(at).isAir()) {
                continue;
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = at.relative(direction);
                if (!plain.containsKey(neighbor)) {
                    ore = neighbor;
                    break;
                }
            }
            if (ore != null) {
                break;
            }
        }
        helper.assertTrue(ore != null, "found a wall beside the planned mine");
        DraftKit.Ground world = new DraftKit.Ground(64).floorAt(-64);
        world.place(ore.getX(), ore.getY(), ore.getZ(), Blocks.IRON_ORE.defaultBlockState());
        Map<BlockPos, BlockState> chased = cells(helper, pattern, settings.flag("chase", true), world);
        helper.assertTrue(chased.containsKey(ore) && chased.get(ore).isAir(), "the exposed ore is dug");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void waterAndLavaPocketsAreSealedAndDrained(GameTestHelper helper) {
        Pattern pattern = mine();
        DraftKit.Ground world = new DraftKit.Ground(64).floorAt(-64);
        BlockPos waterInside = new BlockPos(23, 17, 2);
        BlockPos waterOutside = new BlockPos(23, 17, 3);
        BlockPos lavaInside = new BlockPos(26, 18, 0);
        BlockPos lavaOutside = new BlockPos(26, 18, -1);
        for (int x = 21; x <= 23; x++) {
            world.place(x, 17, 2, Blocks.WATER.defaultBlockState());
        }
        for (int x = 24; x <= 26; x++) {
            world.place(x, 18, 0, Blocks.LAVA.defaultBlockState());
        }
        for (int y = 17; y <= 19; y++) {
            world.place(waterOutside.getX(), y, waterOutside.getZ(), Blocks.WATER.defaultBlockState());
            world.place(lavaOutside.getX(), y, lavaOutside.getZ(), Blocks.LAVA.defaultBlockState());
        }
        Map<BlockPos, BlockState> drawn = cells(helper, pattern, settings(pattern).count("main_south", 0).count("main_west", 0)
            .count("main_north", 0).count("branch_east", 0), world);
        for (BlockPos at : new BlockPos[] {waterInside, waterInside.west(), waterInside.west(2),
                lavaInside, lavaInside.west(), lavaInside.west(2)}) {
            helper.assertTrue(drawn.containsKey(at) && drawn.get(at).isAir(), "fluid is cleared at " + at);
        }
        for (BlockPos outside : new BlockPos[] {waterOutside, lavaOutside}) {
            for (int y = 17; y <= 19; y++) {
                BlockPos at = outside.atY(y);
                helper.assertTrue(drawn.getOrDefault(at, Blocks.AIR.defaultBlockState()).is(Blocks.COBBLESTONE),
                    "the pocket is sealed at " + at);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void dryCaveWallsCeilingAndFloorAreSealed(GameTestHelper helper) {
        Pattern pattern = mine();
        DraftKit.Ground world = new DraftKit.Ground(64).floorAt(-64);
        BlockPos wall = new BlockPos(20, 18, 3);
        BlockPos roof = new BlockPos(20, 20, 1);
        BlockPos floor = new BlockPos(20, 16, 1);
        BlockPos junctionFloor = new BlockPos(1, 16, 8);
        for (BlockPos at : new BlockPos[] {wall, roof, floor, junctionFloor}) {
            world.place(at.getX(), at.getY(), at.getZ(), Blocks.AIR.defaultBlockState());
        }
        Map<BlockPos, BlockState> drawn = cells(helper, pattern, settings(pattern).count("main_south", 0).count("main_west", 0)
            .count("main_north", 0).count("branch_east", 0), world);
        for (BlockPos at : new BlockPos[] {wall, roof, floor}) {
            helper.assertTrue(drawn.getOrDefault(at, Blocks.AIR.defaultBlockState()).is(Blocks.COBBLESTONE),
                "the cave is sealed at " + at);
        }
        helper.assertTrue(drawn.getOrDefault(junctionFloor, Blocks.AIR.defaultBlockState()).is(Blocks.SPRUCE_PLANKS),
            "the junction keeps its plank floor");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void protectedBlocksInPassagesAreLeftAlone(GameTestHelper helper) {
        Pattern pattern = mine();
        BlockPos protectedCell = new BlockPos(27, 17, 1);
        DraftKit.Ground ground = new DraftKit.Ground(64).floorAt(-64);
        ground.place(protectedCell.getX(), protectedCell.getY(), protectedCell.getZ(),
            Blocks.SPRUCE_PLANKS.defaultBlockState());
        Map<BlockPos, BlockState> drawn = cells(helper, pattern, settings(pattern), ground);
        helper.assertTrue(!drawn.containsKey(protectedCell), "protected material is not excavated");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 400)
    public static void adjacentWaterAndFallingBlocksAreSecured(GameTestHelper helper) {
        Pattern pattern = mine();
        DraftKit.Filled settings = settings(pattern);
        Map<BlockPos, BlockState> plain = cells(helper, pattern, settings, new DraftKit.Ground(64).floorAt(-64));
        BlockPos water = null;
        BlockPos gravel = null;
        for (BlockPos at : plain.keySet()) {
            if (!plain.get(at).isAir() || at.getY() < 17 || at.getY() > 19) {
                continue;
            }
            if (gravel == null && !plain.containsKey(at.above())) {
                gravel = at.above();
            }
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (water == null && !plain.containsKey(at.relative(direction))) {
                    water = at.relative(direction);
                }
            }
            if (water != null && gravel != null && !water.equals(gravel)) {
                break;
            }
        }
        helper.assertTrue(water != null && gravel != null && !water.equals(gravel), "found a wall and roof");
        DraftKit.Ground ground = new DraftKit.Ground(64).floorAt(-64);
        ground.place(water.getX(), water.getY(), water.getZ(), Blocks.WATER.defaultBlockState());
        ground.place(gravel.getX(), gravel.getY(), gravel.getZ(), Blocks.GRAVEL.defaultBlockState());
        Map<BlockPos, BlockState> drawn = cells(helper, pattern, settings, ground);
        helper.assertTrue(drawn.getOrDefault(water, Blocks.AIR.defaultBlockState()).is(Blocks.COBBLESTONE),
            "water is sealed");
        helper.assertTrue(drawn.getOrDefault(gravel, Blocks.AIR.defaultBlockState()).is(Blocks.COBBLESTONE),
            "gravel is propped");
        helper.succeed();
    }
}
