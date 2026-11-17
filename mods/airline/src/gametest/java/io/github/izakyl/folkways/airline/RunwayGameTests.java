package io.github.izakyl.folkways.airline;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysAirlineMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_airline")
@PrefixGameTestTemplate(false)
public final class RunwayGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RunwayGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void runwayMarkingsSurveyAndAircraftFit(GameTestHelper helper) {
        var cells = new java.util.HashMap<BlockPos, Integer>();
        Runway expected = new Runway(BlockPos.ZERO, new BlockPos(95, 0, 0), 15);
        AirportMarkings.runway(cells, expected);
        AirportSurface surface = surface(cells);
        helper.assertValueEqual(Runways.diagonal(surface, java.util.List.copyOf(cells.keySet())),
            java.util.List.of(expected), "edge lines and piano keys");
        for (int x = -5; x <= 100; x++) {
            for (int z = -30; z <= 30; z++) {
                cells.putIfAbsent(new BlockPos(x, 0, z), AirportSurface.GRAY);
            }
        }
        helper.assertValueEqual(Runways.diagonal(surface, java.util.List.copyOf(cells.keySet())),
            java.util.List.of(expected), "continuous pavement preserves runway");
        for (int x = 2; x < 6; x++) {
            cells.put(new BlockPos(x, 0, 0), AirportSurface.GRAY);
            cells.put(new BlockPos(x, 0, 2), AirportSurface.GRAY);
            cells.put(new BlockPos(x, 0, -2), AirportSurface.GRAY);
            cells.put(new BlockPos(x, 0, 4), AirportSurface.GRAY);
            cells.put(new BlockPos(x, 0, -4), AirportSurface.GRAY);
        }
        helper.assertTrue(Runways.diagonal(surface, java.util.List.copyOf(cells.keySet())).isEmpty(),
            "missing threshold keys refused");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void diagonalHandBuiltRunwayTolerance(GameTestHelper helper) {
        var cells = new java.util.HashMap<BlockPos, Integer>();
        Runway expected = new Runway(BlockPos.ZERO, new BlockPos(32, 0, -55), 15);
        AirportMarkings.runway(cells, expected);
        var found = Runways.diagonal(surface(cells), java.util.List.copyOf(cells.keySet()));
        helper.assertValueEqual(found.size(), 1, "diagonal edge lines");
        helper.assertValueEqual(found.getFirst().width(), 15, "diagonal width");
        cells.replaceAll((pos, material) -> expected.station(pos) >= 25 && expected.station(pos) <= 29
            ? AirportSurface.OTHER : material);
        helper.assertTrue(Runways.diagonal(surface(cells), java.util.List.copyOf(cells.keySet())).isEmpty(),
            "crossing hole refused");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void diagonalRunwayHeadingsAndWidths(GameTestHelper helper) {
        for (int degrees : new int[] {5, 15, 30, 45, 60, 75, 85, 95, 135, 175, 185, 225, 265, 315, 355}) {
            for (int width : new int[] {5, 15, 63}) {
                double heading = Math.toRadians(degrees);
                Runway expected = new Runway(BlockPos.ZERO, new BlockPos((int) Math.round(Math.sin(heading) * 80),
                    0, (int) Math.round(-Math.cos(heading) * 80)), width);
                java.util.Map<BlockPos, Integer> cells = new java.util.HashMap<>();
                Footprint area = Airport.footprint(expected);
                for (int x = area.minX(); x <= area.maxX(); x++) {
                    for (int z = area.minZ(); z <= area.maxZ(); z++) {
                        BlockPos pos = new BlockPos(x, 0, z);
                        if (area.contains(pos)) {
                            double d = expected.station(pos);
                            double side = expected.lateral(pos);
                            boolean white = Math.abs(side) >= width / 2.0 - 1
                                || (d >= 2 && d < 6 || d > expected.distance() - 6 && d <= expected.distance() - 2)
                                && Math.abs(side) < width / 2.0 - 2 && Math.round(side) % 2 == 0;
                            cells.put(pos, white ? AirportSurface.WHITE : AirportSurface.GRAY);
                        }
                    }
                }
                AirportSurface surface = new AirportSurface() {
                    public int material(BlockPos pos) {
                        return cells.getOrDefault(pos, OTHER);
                    }

                    public boolean loaded(BlockPos pos) {
                        return true;
                    }
                };
                Airport airport = new AirportSurvey(surface, cells.keySet(), BlockPos.ZERO, false).scan();
                helper.assertValueEqual(airport.runways().size(), 1, "heading " + degrees + " width " + width);
                helper.assertValueEqual(airport.runways().getFirst().width(), width, "measured width at " + degrees);
                helper.assertTrue(Math.abs(airport.runways().getFirst().length() - expected.length()) <= 1,
                    "measured length at " + degrees);
            }
        }
        helper.succeed();
    }

    private static AirportSurface surface(java.util.Map<BlockPos, Integer> cells) {
        return new AirportSurface() {
            public int material(BlockPos pos) {
                return cells.getOrDefault(pos, OTHER);
            }

            public boolean loaded(BlockPos pos) {
                return true;
            }
        };
    }

    private static void build(GameTestHelper helper, BlockPos origin, Direction along, Direction across,
                              int length, int width) {
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < width; j++) {
                boolean white = i == 0 || i == length - 1 || (j == width / 2 && i % 2 == 0);
                helper.getLevel().setBlockAndUpdate(origin.relative(along, i).relative(across, j - width / 2),
                    (white ? Blocks.WHITE_CONCRETE : Blocks.GRAY_CONCRETE).defaultBlockState());
            }
        }
    }

    private static void clear(GameTestHelper helper, BlockPos origin, Direction along, Direction across,
                              int length, int width) {
        for (int i = 0; i < length; i++) {
            for (int j = 0; j < width; j++) {
                helper.getLevel().setBlockAndUpdate(origin.relative(along, i).relative(across, j - width / 2),
                    Blocks.AIR.defaultBlockState());
            }
        }
    }
}
