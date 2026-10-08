package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.IntBinaryOperator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TerracedFieldGameTests {

    private static final String SOURCE = "/data/folkways/folkways/pattern/terraced_field.star";

    private TerracedFieldGameTests() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TerracedFieldGameTests.class);
    }

    private static Pattern pattern() {
        try (InputStream in = TerracedFieldGameTests.class.getResourceAsStream(SOURCE)) {
            if (in == null) {
                throw new AssertionError("the terraced field pattern is not bundled");
            }
            return Pattern.parse(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "terraced_field"),
                new String(in.readAllBytes(), StandardCharsets.UTF_8)).orElseThrow(
                    () -> new AssertionError("the terraced field pattern did not load"));
        } catch (IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static Drawn draw(Pattern pattern, int width, int depth, IntBinaryOperator height) {
        return draw(pattern, width, depth, height, Direction.NORTH);
    }

    private static Drawn draw(Pattern pattern, int width, int depth, IntBinaryOperator height, Direction facing) {
        return draw(pattern, width, depth, height, facing, DraftKit.Filled.defaulting(pattern.knobs()));
    }

    private static Drawn draw(Pattern pattern, int width, int depth, IntBinaryOperator height, Direction facing,
            DraftKit.Filled knobs) {
        var ground = new DraftKit.Ground(0);
        for (int x = -4; x < width + 4; x++) {
            for (int z = -4; z < depth + 4; z++) {
                ground.column(x, z, height.applyAsInt(x, z));
            }
        }
        var hint = new Hint.Zone(BlockPos.ZERO, new BlockPos(width - 1, 2, depth - 1));
        return pattern.drawOn(new Commission(hint, knobs, ground, facing, 0L));
    }

    private static Map<BlockPos, Draft.Cell> placed(Drawn.Ready ready) {
        Map<BlockPos, Draft.Cell> cells = new HashMap<>();
        for (Draft.Cell cell : ready.draft().cells()) {
            cells.put(ready.corner().offset(cell.offset()), cell);
        }
        return cells;
    }

    private static Drawn.Ready ready(GameTestHelper helper, Drawn drawn) {
        DraftKit.ready(helper, drawn);
        return (Drawn.Ready) drawn;
    }

    private static Set<Integer> fieldLevels(GameTestHelper helper, Map<BlockPos, Draft.Cell> cells) {
        Set<Integer> levels = new TreeSet<>();
        int soil = 0;
        for (var entry : cells.entrySet()) {
            BlockPos at = entry.getKey();
            Draft.Cell cell = entry.getValue();
            Draft.Cell above = cells.get(at.above());
            if (!cell.source().role().equals("fill") || (above != null && !above.state().isAir())) {
                continue;
            }
            helper.assertTrue(cell.state().is(Blocks.DIRT), "the field is laid in tillable soil at " + at);
            soil++;
            levels.add(at.getY());
            boolean wet = false;
            for (int dx = -4; dx <= 4 && !wet; dx++) {
                for (int dz = -4; dz <= 4 && !wet; dz++) {
                    for (int dy = 0; dy <= 1 && !wet; dy++) {
                        Draft.Cell near = cells.get(at.offset(dx, dy, dz));
                        wet = near != null && near.state().is(Blocks.WATER);
                    }
                }
            }
            helper.assertTrue(wet, "soil at " + at + " lies within four blocks of a channel");
        }
        helper.assertTrue(soil > 0, "the field lays soil");
        return levels;
    }

    private static void assertGateInLine(GameTestHelper helper, Map<BlockPos, Draft.Cell> cells) {
        int gates = 0;
        for (var entry : cells.entrySet()) {
            BlockState gate = entry.getValue().state();
            if (!gate.is(Blocks.SPRUCE_FENCE_GATE)) {
                continue;
            }
            gates++;
            Direction along = gate.getValue(FenceGateBlock.FACING).getClockWise();
            for (BlockPos beside : List.of(entry.getKey().relative(along), entry.getKey().relative(along.getOpposite()))) {
                Draft.Cell fence = cells.get(beside);
                helper.assertTrue(fence != null && fence.state().is(Blocks.SPRUCE_FENCE),
                    "the gate at " + entry.getKey() + " swings in line with the fence");
            }
        }
        helper.assertValueEqual(gates, 1, "one gate");
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void terracedFieldParsesAsAZonePattern(GameTestHelper helper) {
        Pattern pattern = pattern();
        helper.assertTrue(pattern.accepts().contains("zone"), "a terraced field is drawn on a zone");
        helper.assertTrue(pattern.knobs().settings().size() >= 10, "materials, rise, spacing and fencing are knobs");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void flatZoneBecomesOneLevelFieldWithEverySoilCellHydrated(GameTestHelper helper) {
        var ready = ready(helper, draw(pattern(), 21, 17, (x, z) -> 0));
        var cells = placed(ready);
        helper.assertValueEqual(fieldLevels(helper, cells), Set.of(0), "a flat field keeps one level");
        helper.assertTrue(cells.values().stream().anyMatch(cell -> cell.state().is(Blocks.SPRUCE_FENCE_GATE)),
            "the fence has a gate");
        helper.assertTrue(cells.values().stream().anyMatch(cell -> cell.state().is(Blocks.LANTERN)),
            "the field is lit");
        helper.assertTrue(cells.values().stream().noneMatch(cell -> cell.state().getBlock() instanceof StairBlock),
            "a level field needs no steps");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void slopedZoneStepsIntoSeveralLevelTerraces(GameTestHelper helper) {
        for (Direction facing : List.of(Direction.NORTH, Direction.EAST)) {
            var along = ready(helper, draw(pattern(), 20, 28, (x, z) -> z / 3, facing));
            var cells = placed(along);
            helper.assertTrue(fieldLevels(helper, cells).size() >= 3, "a slope along z becomes terraces");
            helper.assertTrue(cells.values().stream().anyMatch(cell -> cell.state().getBlock() instanceof StairBlock),
                "steps climb between terraces");
            assertGateInLine(helper, cells);
            var across = placed(ready(helper, draw(pattern(), 28, 20, (x, z) -> x / 3, facing)));
            helper.assertTrue(fieldLevels(helper, across).size() >= 3, "a slope along x becomes terraces");
            assertGateInLine(helper, across);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void everyStepHasHeadroomToClimbOut(GameTestHelper helper) {
        IntBinaryOperator slope = (x, z) -> z / 3;
        var cells = placed(ready(helper, draw(pattern(), 20, 28, slope)));
        int steps = 0;
        for (var entry : cells.entrySet()) {
            if (!(entry.getValue().state().getBlock() instanceof StairBlock)) {
                continue;
            }
            steps++;
            for (int up = 1; up <= 2; up++) {
                BlockPos head = entry.getKey().above(up);
                Draft.Cell over = cells.get(head);
                boolean open = over == null
                    ? head.getY() > slope.applyAsInt(head.getX(), head.getZ())
                    : !over.state().blocksMotion();
                helper.assertTrue(open, "the step at " + entry.getKey() + " is roofed over at " + head);
            }
        }
        helper.assertTrue(steps > 0, "the terraces are joined by steps");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aFewWaterHolesHydrateTheWholeField(GameTestHelper helper) {
        var cells = placed(ready(helper, draw(pattern(), 21, 17, (x, z) -> 0)));
        long water = cells.values().stream().filter(cell -> cell.state().is(Blocks.WATER)).count();
        fieldLevels(helper, cells);
        helper.assertValueEqual(water, 4L, "two strips of 9 by 15 need two holes each");
        long lamps = cells.values().stream().filter(cell -> cell.state().is(Blocks.LANTERN)).count();
        assertLitThrough(helper, cells);
        helper.assertTrue(lamps <= 6, "a 21 by 17 field is lit by a handful of lanterns, not " + lamps);
        helper.succeed();
    }

    private static void assertLitThrough(GameTestHelper helper, Map<BlockPos, Draft.Cell> cells) {
        List<BlockPos> lamps = cells.entrySet().stream()
            .filter(entry -> entry.getValue().state().is(Blocks.LANTERN)).map(Map.Entry::getKey).toList();
        for (var entry : cells.entrySet()) {
            Draft.Cell above = cells.get(entry.getKey().above());
            if (!entry.getValue().source().role().equals("fill") || (above != null && !above.state().isAir())) {
                continue;
            }
            BlockPos stand = entry.getKey().above();
            helper.assertTrue(lamps.stream().anyMatch(lamp -> lamp.distManhattan(stand) <= 14),
                "no lantern keeps the dark off " + stand);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void terracesFollowTheLieOfTheLand(GameTestHelper helper) {
        IntBinaryOperator hill = (x, z) -> (z + (int) Math.round(5 * Math.sin(x * 0.25))) / 3;
        var cells = placed(ready(helper, draw(pattern(), 32, 30, hill)));
        fieldLevels(helper, cells);
        assertLitThrough(helper, cells);
        for (var entry : cells.entrySet()) {
            BlockPos at = entry.getKey();
            Draft.Cell above = cells.get(at.above());
            if (!entry.getValue().source().role().equals("fill") || (above != null && !above.state().isAir())) {
                continue;
            }
            int cut = at.getY() - hill.applyAsInt(at.getX(), at.getZ());
            helper.assertTrue(Math.abs(cut) <= 2, "the terrace at " + at + " sits " + cut + " off the ground");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aGentleSlopeClimbsInSingleBlockSteps(GameTestHelper helper) {
        var cells = placed(ready(helper, draw(pattern(), 20, 28, (x, z) -> z / 3)));
        Set<Integer> levels = fieldLevels(helper, cells);
        helper.assertTrue(levels.size() >= 8, "a nine-block rise is climbed in many small steps, not " + levels);
        helper.assertValueEqual(levels.size(), levels.stream().mapToInt(Integer::intValue).max().orElseThrow()
            - levels.stream().mapToInt(Integer::intValue).min().orElseThrow() + 1, "every step is one block");
        for (var entry : cells.entrySet()) {
            Draft.Cell above = cells.get(entry.getKey().above());
            helper.assertTrue(!entry.getValue().source().role().equals("bund")
                    || (above != null && above.state().is(Blocks.SPRUCE_FENCE)),
                "a one-block step needs no bank, yet one stands at " + entry.getKey());
        }
        assertLitThrough(helper, cells);
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void lowTerracesAreBankedInEarthNotStone(GameTestHelper helper) {
        Pattern pattern = pattern();
        var cells = placed(ready(helper, draw(pattern, 20, 28, (x, z) -> z / 3, Direction.NORTH,
            DraftKit.Filled.defaulting(pattern.knobs()).count("rise", 2))));
        helper.assertTrue(cells.values().stream().anyMatch(cell -> cell.source().role().equals("bund")
            && cell.state().is(Blocks.GRASS_BLOCK)), "two-block terraces are held by turfed earth bunds");
        helper.assertTrue(cells.values().stream().noneMatch(cell -> cell.state().is(Blocks.COBBLESTONE)
            || cell.state().is(Blocks.MOSSY_COBBLESTONE)), "no stone wall where an earth bank will do");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void theFieldStopsShortOfARiseItCannotTerrace(GameTestHelper helper) {
        var cells = placed(ready(helper, draw(pattern(), 24, 16, (x, z) -> x >= 16 && z >= 10 ? 6 : 0)));
        for (BlockPos at : cells.keySet()) {
            helper.assertTrue(at.getX() < 16 || at.getZ() < 10, "the knoll is left as it is, yet the field touches " + at);
        }
        helper.assertTrue(cells.values().stream().anyMatch(cell -> cell.source().role().equals("wall")),
            "a wall holds back the plateau where the field is cut into it");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 80)
    public static void terracedFieldRefusesBadSites(GameTestHelper helper) {
        Pattern pattern = pattern();
        DraftKit.refused(helper, draw(pattern, 4, 12, (x, z) -> 0));
        DraftKit.refused(helper, draw(pattern, 20, 20, (x, z) -> 2 * z));
        var path = new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(12, 0, 0)));
        helper.assertValueEqual(DraftKit.refused(helper, DraftKit.draw(pattern, path)).why(),
            DraftRefusal.WRONG_MARK, "a path is not a field");
        helper.succeed();
    }
}
