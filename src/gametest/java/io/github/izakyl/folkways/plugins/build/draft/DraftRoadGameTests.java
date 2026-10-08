package io.github.izakyl.folkways.plugins.build.draft;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftRoadGameTests {

    private static final String TEMPLATE = "empty";
    private static final Set<String> WAY = Set.of("surface", "edge");

    private DraftRoadGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftRoadGameTests.class);
    }

    private static Pattern road() {
        return Patterns.find(ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "road"))
            .orElseThrow(() -> new AssertionError("the bundled road pattern did not load"));
    }

    private static DraftKit.Filled settings(Pattern pattern) {
        return DraftKit.Filled.defaulting(pattern.knobs());
    }

    private static Drawn draw(Pattern pattern, World world, DraftKit.Filled settings, BlockPos... points) {
        return pattern.drawOn(new Commission(new Hint.Path(List.of(points)), settings, world, Direction.NORTH, 0L));
    }

    private static Drawn.Ready ready(GameTestHelper helper, Drawn drawn) {
        DraftKit.ready(helper, drawn);
        return (Drawn.Ready) drawn;
    }

    private static Map<BlockPos, Double> tops(Drawn.Ready drawn, Set<String> roles) {
        Map<BlockPos, Double> tops = new HashMap<>();
        for (Draft.Cell cell : drawn.draft().cells()) {
            if (!roles.contains(cell.source().role())) {
                continue;
            }
            BlockPos at = drawn.corner().offset(cell.offset());
            double top = at.getY() + (cell.state().getBlock() instanceof SlabBlock
                && cell.state().getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? 0.5D : 1.0D);
            tops.merge(new BlockPos(at.getX(), 0, at.getZ()), top, Math::max);
        }
        return tops;
    }

    private static Map<BlockPos, String> placed(Drawn.Ready drawn) {
        Map<BlockPos, String> roles = new HashMap<>();
        for (Draft.Cell cell : drawn.draft().cells()) {
            roles.put(drawn.corner().offset(cell.offset()), cell.source().role());
        }
        return roles;
    }

    private static void walkable(GameTestHelper helper, Map<BlockPos, Double> tops) {
        for (Map.Entry<BlockPos, Double> column : tops.entrySet()) {
            for (Direction side : Direction.Plane.HORIZONTAL) {
                Double beside = tops.get(column.getKey().relative(side));
                if (beside != null && Math.abs(beside - column.getValue()) > 0.5001D) {
                    helper.fail("a step of " + Math.abs(beside - column.getValue()) + " between "
                        + column.getKey().toShortString() + " and " + column.getKey().relative(side).toShortString());
                }
            }
        }
    }

    private static int pieces(Set<BlockPos> columns) {
        Set<BlockPos> left = new HashSet<>(columns);
        int found = 0;
        while (!left.isEmpty()) {
            found++;
            ArrayDeque<BlockPos> queue = new ArrayDeque<>();
            BlockPos first = left.iterator().next();
            left.remove(first);
            queue.add(first);
            while (!queue.isEmpty()) {
                BlockPos at = queue.removeFirst();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = at.offset(dx, 0, dz);
                        if (left.remove(next)) {
                            queue.add(next);
                        }
                    }
                }
            }
        }
        return found;
    }

    private static Set<BlockPos> columnsOf(Drawn.Ready drawn, String role) {
        Set<BlockPos> found = new HashSet<>();
        for (Draft.Cell cell : drawn.draft().cells()) {
            if (cell.source().role().equals(role)) {
                BlockPos at = drawn.corner().offset(cell.offset());
                found.add(new BlockPos(at.getX(), 0, at.getZ()));
            }
        }
        return found;
    }

    private static String grid(Drawn.Ready drawn) {
        Map<BlockPos, String> roles = new HashMap<>();
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Draft.Cell cell : drawn.draft().cells()) {
            BlockPos at = drawn.corner().offset(cell.offset());
            String role = cell.source().role();
            BlockPos key = new BlockPos(at.getX(), 0, at.getZ());
            if (role.equals("edge") || (role.equals("surface") && !"edge".equals(roles.get(key)))) {
                roles.put(key, role);
            }
            minX = Math.min(minX, at.getX()); maxX = Math.max(maxX, at.getX());
            minZ = Math.min(minZ, at.getZ()); maxZ = Math.max(maxZ, at.getZ());
        }
        StringBuilder out = new StringBuilder();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                String role = roles.get(new BlockPos(x, 0, z));
                out.append(role == null ? '.' : role.equals("edge") ? 'e' : '#');
            }
            out.append('\n');
        }
        return out.toString();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void theRoadPatternShipsAndTakesAPath(GameTestHelper helper) {
        Pattern pattern = road();
        helper.assertTrue(pattern.accepts(new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(9, 0, 0)))),
            "a road follows a path");
        helper.assertFalse(pattern.accepts(DraftKit.zone(4, 1, 4)), "a road is not drawn in a zone");
        helper.assertTrue(pattern.knobs().settings().stream().anyMatch(knob -> knob.key().equals("width")),
            "the road's width is a knob");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aStraightRoadLiesFlushInFlatGround(GameTestHelper helper) {
        Pattern pattern = road();
        Drawn.Ready drawn = ready(helper, draw(pattern, new DraftKit.Ground(64), settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)));
        Map<BlockPos, String> roles = placed(drawn);
        for (int x = 0; x <= 24; x++) {
            for (int z = -2; z <= 2; z++) {
                String expected = Math.abs(z) == 2 ? "edge" : "surface";
                helper.assertValueEqual(roles.get(new BlockPos(x, 64, z)), expected, "road cell at " + x + ", " + z);
            }
            helper.assertTrue(roles.get(new BlockPos(x, 64, 3)) == null, "no road beyond the edge at " + x);
        }
        Map<BlockPos, Double> tops = tops(drawn, WAY);
        helper.assertTrue(tops.values().stream().allMatch(top -> top == 65.0D), "the flat road is flush");
        helper.assertTrue(roles.values().stream().noneMatch(role -> role.equals("fill") || role.equals("dig")),
            "flat ground needs no earthworks");
        helper.assertTrue(roles.containsValue("lamp") && roles.containsValue("post"), "streetlights stand by the road");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void cornersKeepTheRoadWholeAndItsEdgesUnbroken(GameTestHelper helper) {
        Pattern pattern = road();
        for (BlockPos[] path : List.of(
                new BlockPos[] {new BlockPos(0, 64, 0), new BlockPos(20, 64, 0), new BlockPos(20, 64, 20)},
                new BlockPos[] {new BlockPos(0, 64, 0), new BlockPos(16, 64, 0), new BlockPos(30, 64, 14)},
                new BlockPos[] {new BlockPos(0, 64, 0), new BlockPos(14, 64, 14), new BlockPos(14, 64, 30)})) {
            for (int width : new int[] {3, 4, 5, 8}) {
                Drawn.Ready drawn = ready(helper, draw(pattern, new DraftKit.Ground(64),
                    settings(pattern).count("width", width), path));
                Set<BlockPos> way = tops(drawn, WAY).keySet();
                helper.assertValueEqual(pieces(way), 1, "one road at width " + width);
                helper.assertValueEqual(pieces(columnsOf(drawn, "surface")), 1, "an unbroken surface at width " + width
                    + " along " + List.of(path) + "\n" + grid(drawn));
                if (width > 3) {
                    helper.assertValueEqual(pieces(columnsOf(drawn, "edge")), 2, "two unbroken edges at width "
                        + width + " along " + List.of(path) + "\n" + grid(drawn));
                }
                for (BlockPos point : path) {
                    helper.assertTrue(way.contains(new BlockPos(point.getX(), 0, point.getZ())),
                        "the road passes " + point.toShortString());
                }
                BlockPos start = new BlockPos(path[0].getX(), 0, path[0].getZ());
                BlockPos end = new BlockPos(path[path.length - 1].getX(), 0, path[path.length - 1].getZ());
                for (BlockPos column : way) {
                    if (column.distSqr(start) <= width * width || column.distSqr(end) <= width * width) {
                        continue;
                    }
                    int around = 0;
                    for (Direction side : Direction.Plane.HORIZONTAL) {
                        around += way.contains(column.relative(side)) ? 1 : 0;
                    }
                    helper.assertTrue(around >= 2, "no jagged single cell at " + column.toShortString());
                }
                walkable(helper, tops(drawn, WAY));
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aClimbingRoadIsGradedWithoutAFullBlockStep(GameTestHelper helper) {
        Pattern pattern = road();
        DraftKit.Ground hill = new DraftKit.Ground(64);
        for (int x = -12; x <= 60; x++) {
            for (int z = -24; z <= 40; z++) {
                int rise = Math.max(0, Math.min(10, x - 8)) + (x > 30 && x < 34 ? 3 : 0) - (x > 40 && x < 44 ? 2 : 0);
                hill.column(x, z, 64 + rise);
            }
        }
        Drawn.Ready drawn = ready(helper, draw(pattern, hill, settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(30, 77, 0), new BlockPos(48, 74, 16)));
        Map<BlockPos, Double> tops = tops(drawn, WAY);
        walkable(helper, tops);
        helper.assertValueEqual(tops.get(new BlockPos(0, 0, 0)), 65.0D, "the road meets the ground where it starts");
        helper.assertValueEqual(tops.get(new BlockPos(48, 0, 16)), 75.0D, "and where it ends");
        helper.assertTrue(tops.values().stream().anyMatch(top -> top % 1.0D != 0.0D), "a grade is laid in half slabs");
        Map<BlockPos, String> roles = placed(drawn);
        helper.assertTrue(roles.containsValue("dig"), "the hump is cut through");
        helper.assertTrue(roles.containsValue("fill"), "the dip is filled");
        for (BlockPos column : tops.keySet()) {
            int y = (int) Math.floor(tops.get(column) - 0.5D);
            for (int below = y - 1; below > y - 12; below--) {
                BlockPos at = new BlockPos(column.getX(), below, column.getZ());
                boolean held = roles.containsKey(at) && !roles.get(at).equals("dig") && !roles.get(at).equals("canopy");
                boolean ground = !roles.containsKey(at) && hill.block(at).orElseThrow().is(Blocks.STONE);
                if (ground) {
                    break;
                }
                helper.assertTrue(held, "the road is carried down to the ground under " + at.toShortString());
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aShortWaterCrossingBecomesACauseway(GameTestHelper helper) {
        Pattern pattern = road();
        DraftKit.Ground pond = new DraftKit.Ground(64)
            .water(at -> at.getX() >= 10 && at.getX() <= 16 && at.getY() <= 64);
        for (int x = 10; x <= 16; x++) {
            for (int z = -20; z <= 20; z++) {
                pond.column(x, z, 61);
            }
        }
        Drawn.Ready drawn = ready(helper, draw(pattern, pond, settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(26, 64, 0)));
        Map<BlockPos, Double> tops = tops(drawn, WAY);
        walkable(helper, tops);
        for (int x = 10; x <= 16; x++) {
            helper.assertTrue(tops.get(new BlockPos(x, 0, 0)) >= 66.0D, "the causeway stands clear of the water at " + x);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void roadsThatCannotBeBuiltAreRefusedInPlainWords(GameTestHelper helper) {
        Pattern pattern = road();
        DraftKit.Ground flat = new DraftKit.Ground(64);
        helper.assertValueEqual(pattern.drawOn(new Commission(DraftKit.zone(8, 1, 8), settings(pattern), flat,
            Direction.NORTH, 0L)) instanceof Drawn.Refused refused ? refused.why() : null, DraftRefusal.WRONG_MARK,
            "a zone is the wrong mark");
        Drawn.Refused back = DraftKit.refused(helper, draw(pattern, flat, settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(20, 64, 0), new BlockPos(2, 64, 1)));
        helper.assertTrue(back.detail().contains("135") && back.detail().contains("转"), "doubling back is explained");
        DraftKit.Ground cliff = new DraftKit.Ground(64);
        for (int x = 10; x <= 40; x++) {
            for (int z = -20; z <= 20; z++) {
                cliff.column(x, z, 84);
            }
        }
        Drawn.Refused steep = DraftKit.refused(helper, draw(pattern, cliff, settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(14, 84, 0)));
        helper.assertTrue(steep.detail().contains("steep") && steep.detail().contains("坡度"), "too steep: " + steep.detail());
        DraftKit.Ground lake = new DraftKit.Ground(64).water(at -> at.getX() >= 4 && at.getX() <= 40 && at.getY() <= 64);
        for (int x = 4; x <= 40; x++) {
            for (int z = -20; z <= 20; z++) {
                lake.column(x, z, 62);
            }
        }
        Drawn.Refused wide = DraftKit.refused(helper, draw(pattern, lake, settings(pattern),
            new BlockPos(0, 64, 0), new BlockPos(46, 64, 0)));
        helper.assertTrue(wide.detail().contains("arch bridge") && wide.detail().contains("拱桥"),
            "a wide water points at the bridge: " + wide.detail());
        Drawn.Refused narrow = DraftKit.refused(helper, draw(pattern, flat, settings(pattern).count("width", 2),
            new BlockPos(0, 64, 0), new BlockPos(20, 64, 0)));
        helper.assertTrue(narrow.detail().contains("道路宽度"), "a width out of range is explained");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void streetlightsCanBeLeftOut(GameTestHelper helper) {
        Pattern pattern = road();
        Drawn.Ready drawn = ready(helper, draw(pattern, new DraftKit.Ground(64), settings(pattern).flag("lights", false),
            new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)));
        helper.assertTrue(!placed(drawn).containsValue("lamp") && !placed(drawn).containsValue("post"),
            "no lights when switched off");
        helper.succeed();
    }
}
