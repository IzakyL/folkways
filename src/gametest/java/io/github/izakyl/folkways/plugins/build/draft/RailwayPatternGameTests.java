package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.build.Joints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RailwayPatternGameTests {

    private static final String TEMPLATE = "empty";

    private static final ResourceLocation RAILWAY = ResourceLocation.fromNamespaceAndPath("folkways", "railway");

    private RailwayPatternGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RailwayPatternGameTests.class);
    }

    static Pattern railway() {
        return Patterns.find(RAILWAY).orElseThrow(() -> new AssertionError("the railway pattern did not load"));
    }

    static Drawn lay(Pattern pattern, World world, BlockPos... points) {
        return pattern.drawOn(new Commission(new Hint.Path(List.of(points)),
            DraftKit.Filled.defaulting(pattern.knobs()), world, Direction.NORTH, 0L));
    }

    static Map<BlockPos, BlockState> cells(GameTestHelper helper, Drawn drawn) {
        if (drawn instanceof Drawn.Refused refused) {
            helper.fail("expected a railway, got " + refused.why() + " " + refused.detail());
        }
        Drawn.Ready ready = (Drawn.Ready) drawn;
        Map<BlockPos, BlockState> found = new HashMap<>();
        for (Draft.Cell cell : ready.draft().cells()) {
            found.put(ready.corner().offset(cell.offset()), cell.state());
        }
        return found;
    }

    static String shape(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("shape")) {
                return named(state, property);
            }
        }
        return "";
    }

    private static <T extends Comparable<T>> String named(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    static boolean track(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals("create:track");
    }

    static String section(Map<BlockPos, BlockState> laid, int z, int fromX, int toX, int fromY, int toY) {
        StringBuilder drawn = new StringBuilder();
        for (int y = toY; y >= fromY; y--) {
            drawn.append(String.format("%4d ", y));
            for (int x = fromX; x <= toX; x++) {
                BlockState state = laid.get(new BlockPos(x, y, z));
                drawn.append(state == null ? '.' : mark(state));
            }
            drawn.append('\n');
        }
        return drawn.toString();
    }

    private static char mark(BlockState state) {
        if (state.isAir()) {
            return '_';
        }
        if (track(state)) {
            return 'T';
        }
        if (state.getBlock() instanceof StairBlock) {
            return 's';
        }
        if (state.getBlock() instanceof SlabBlock) {
            return state.getValue(SlabBlock.TYPE) == SlabType.TOP ? '^' : 'v';
        }
        String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return Character.toUpperCase(name.charAt(0)) == 'T' ? 'B' : name.charAt(0);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aLevelRunLaysStraightTrackOnBallast(GameTestHelper helper) {
        var laid = cells(helper, lay(railway(), new DraftKit.Ground(0), BlockPos.ZERO, new BlockPos(0, 0, 24)));
        System.out.println("LEVEL\n" + section(laid, 12, -8, 8, -4, 6));
        for (int z = 0; z <= 24; z++) {
            BlockState rail = laid.get(new BlockPos(0, 1, z));
            helper.assertTrue(rail != null && track(rail) && shape(rail).equals("zo"), "straight track at z=" + z);
            helper.assertValueEqual(laid.get(new BlockPos(0, 0, z)), Blocks.TUFF.defaultBlockState(), "ballast");
        }
        helper.succeed();
    }

    static List<BlockPos> rails(Map<BlockPos, BlockState> laid) {
        return rails(laid, 1, 1);
    }

    static List<BlockPos> rails(Map<BlockPos, BlockState> laid, int sx, int sz) {
        List<BlockPos> found = new ArrayList<>();
        laid.forEach((at, state) -> {
            if (track(state)) {
                found.add(at);
            }
        });
        found.sort(Comparator.comparingInt((BlockPos at) -> at.getX() * sx + at.getZ() * sz));
        return found;
    }

    static String joined(Map<BlockPos, BlockState> laid, int sx, int sz) {
        List<BlockPos> line = rails(laid, sx, sz);
        String flat = sx != 0 && sz != 0 ? (sx * sz > 0 ? "pd" : "nd") : (sz != 0 ? "zo" : "xo");
        for (int at = 0; at < line.size(); at++) {
            BlockPos here = line.get(at);
            if (!shape(laid.get(here)).equals(flat)) {
                return "track " + shape(laid.get(here)) + " at " + here + " does not run " + flat;
            }
            if (at + 1 < line.size() && !line.get(at + 1).equals(here.offset(sx, 0, sz))) {
                return "a gap between " + here + " and " + line.get(at + 1);
            }
        }
        return "";
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aDiagonalRunLaysDiagonalTrackCornerToCorner(GameTestHelper helper) {
        var laid = cells(helper, lay(railway(), new DraftKit.Ground(0), BlockPos.ZERO, new BlockPos(-12, 0, 12)));
        helper.assertValueEqual(joined(laid, -1, 1), "", "every rail meets the next");
        helper.assertValueEqual(rails(laid).size(), 13, "one rail per diagonal step");
        for (BlockPos at : rails(laid)) {
            helper.assertTrue(laid.get(at.below()).is(Blocks.TUFF), "ballast under " + at);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aHillIsTunnelledWithALinedBore(GameTestHelper helper) {
        var hill = new DraftKit.Ground(0);
        for (int x = -16; x <= 16; x++) {
            for (int z = -2; z <= 30; z++) {
                hill.column(x, z, Math.max(0, 14 - Math.abs(z - 14)));
            }
        }
        var laid = cells(helper, lay(railway(), hill, BlockPos.ZERO, new BlockPos(0, 0, 28)));
        System.out.println("TUNNEL\n" + section(laid, 14, -8, 8, -2, 10));
        helper.assertValueEqual(joined(laid, 0, 1), "", "every rail meets the next");
        for (int y = 2; y <= 4; y++) {
            helper.assertTrue(laid.get(new BlockPos(0, y, 14)).isAir(), "the bore is dug out at y=" + y);
        }
        helper.assertTrue(laid.get(new BlockPos(3, 2, 14)).is(Blocks.BRICKS), "the bore is lined");
        helper.assertTrue(laid.get(new BlockPos(0, 6, 14)).is(Blocks.BRICKS), "the bore has a crown");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aRiverIsCrossedOnArchesWithoutFillingIt(GameTestHelper helper) {
        var river = new DraftKit.Ground(0).water(at -> at.getY() <= -1 && at.getZ() >= 6 && at.getZ() <= 18);
        for (int x = -16; x <= 16; x++) {
            for (int z = 6; z <= 18; z++) {
                river.column(x, z, -7);
            }
        }
        var laid = cells(helper, lay(railway(), river, BlockPos.ZERO, new BlockPos(0, 0, 24)));
        System.out.println("BRIDGE\n" + section(laid, 9, -8, 8, -8, 3));
        helper.assertValueEqual(joined(laid, 0, 1), "", "every rail meets the next");
        helper.assertTrue(laid.get(new BlockPos(0, -1, 12)).is(Blocks.BRICKS), "a deck under the ballast");
        helper.assertTrue(laid.get(new BlockPos(3, 0, 12)).is(Blocks.BRICK_WALL), "a parapet");
        helper.assertTrue(laid.get(new BlockPos(0, -5, 9)) == null, "the arch leaves the river open");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void materialsFollowTheKnobsAndKeepTheirShape(GameTestHelper helper) {
        var pattern = railway();
        var knobs = DraftKit.Filled.defaulting(pattern.knobs()).items("ballast", "minecraft:andesite");
        var laid = cells(helper, pattern.drawOn(new Commission(new Hint.Path(List.of(BlockPos.ZERO,
            new BlockPos(0, 0, 12))), knobs, new DraftKit.Ground(0), Direction.NORTH, 0L)));
        helper.assertTrue(laid.get(new BlockPos(0, 0, 6)).is(Blocks.ANDESITE), "ballast of the chosen stone");
        helper.assertTrue(laid.get(new BlockPos(2, 0, 6)).is(Blocks.ANDESITE_SLAB), "its shoulder stays a slab");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aRailwayRefusesWhatTrackCannotDo(GameTestHelper helper) {
        var pattern = railway();
        var ground = new DraftKit.Ground(0);
        for (BlockPos[] marks : List.of(
                new BlockPos[] {BlockPos.ZERO, new BlockPos(0, 0, 6), new BlockPos(6, 0, 6)},
                new BlockPos[] {BlockPos.ZERO, new BlockPos(0, 0, 24), new BlockPos(0, 0, 8)},
                new BlockPos[] {BlockPos.ZERO, new BlockPos(5, 0, 24)},
                new BlockPos[] {BlockPos.ZERO, new BlockPos(0, 12, 20)},
                new BlockPos[] {BlockPos.ZERO, new BlockPos(0, 0, 2)})) {
            var refusal = refused(helper, lay(pattern, ground, marks));
            helper.assertTrue(refusal.detail().contains(" / "), "a refusal in both languages: " + refusal.detail());
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void blocksLayExactStatesAndSwapKeepsTheirShape(GameTestHelper helper) {
        var draft = DraftKit.ready(helper, DraftKit.draw(parse("""
            def draw(site):
                return blocks("steps", {
                    point(0, 0, 0): "minecraft:oak_stairs[facing=east,half=top]",
                    point(1, 0, 0): "minecraft:oak_slab[type=top]",
                    point(2, 0, 0): "minecraft:oak_planks",
                    point(3, 0, 0): "minecraft:air",
                }, swap = {"minecraft:oak_stairs": ["minecraft:stone_bricks"],
                    "minecraft:oak_slab": ["minecraft:stone_bricks"]})
            """), DraftKit.zone(4, 1, 1)));
        helper.assertValueEqual(DraftKit.stateAt(draft, 0, 0, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()
            .setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP), "a stair stays a stair");
        helper.assertValueEqual(DraftKit.stateAt(draft, 1, 0, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()
            .setValue(SlabBlock.TYPE, SlabType.TOP), "a slab stays a slab");
        helper.assertValueEqual(DraftKit.stateAt(draft, 2, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(),
            "what no swap names is laid as written");
        helper.assertTrue(DraftKit.stateAt(draft, 3, 0, 0).isAir(), "air is laid as a cell to empty");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void createJoinsTheLaidTrackIntoOneRunningLine(GameTestHelper helper) {
        var laid = cells(helper, lay(railway(), new DraftKit.Ground(0), BlockPos.ZERO, new BlockPos(16, 0, 0)));
        BlockPos base = helper.absolutePos(new BlockPos(0, 40, 0));
        laid.forEach((at, state) -> helper.getLevel().setBlock(base.offset(at), state, 3));
        helper.runAfterDelay(20, () -> {
            var ours = new ArrayList<Vec3>();
            int graphs = 0;
            for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                boolean here = false;
                for (TrackNodeLocation node : graph.getNodes()) {
                    Vec3 at = node.getLocation().subtract(Vec3.atLowerCornerOf(base));
                    if (at.x >= -1 && at.x <= 18 && Math.abs(at.y - 1) < 2 && Math.abs(at.z - 0.5) < 1.5) {
                        ours.add(at);
                        here = true;
                    }
                }
                graphs += here ? 1 : 0;
            }
            helper.assertValueEqual(graphs, 1, "the whole line is one track graph");
            helper.assertTrue(ours.stream().allMatch(at -> Math.abs(at.y - 1) < 1e-6 && Math.abs(at.z - 0.5) < 1e-6),
                "every node lies level on the one straight line, so a train can run through each: " + ours);
            double least = ours.stream().mapToDouble(Vec3::x).min().orElseThrow();
            double most = ours.stream().mapToDouble(Vec3::x).max().orElseThrow();
            helper.assertTrue(least <= 0.01 && most >= 16.99, "the edge runs end to end, from " + least + " to " + most);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void everyBundledPatternFitsThePacketThatOffersIt(GameTestHelper helper) {
        Patterns.sources().forEach((id, source) -> helper.assertTrue(source.length() <= PatternLibraryPacket.MAX_SOURCE,
            id + " is " + source.length() + " characters, more than players are sent"));
        helper.assertTrue(Patterns.sources().containsKey(RAILWAY), "the railway is offered");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aJointEndsOnlyOnSomethingTheDrawingLays(GameTestHelper helper) {
        var joined = DraftKit.ready(helper, DraftKit.draw(parse("""
            def draw(site):
                return [blocks("ends", {point(0, 0, 0): "minecraft:stone", point(3, 0, 0): "minecraft:stone"}),
                    joint(point(0, 0, 0), point(3, 0, 0))]
            """), DraftKit.zone(4, 1, 1)));
        helper.assertValueEqual(joined.joints(), List.of(new Joint(BlockPos.ZERO, new BlockPos(3, 0, 0))),
            "the joint rides along with the drawing");
        var loose = refused(helper, DraftKit.draw(parse("""
            def draw(site):
                return [blocks("end", {point(0, 0, 0): "minecraft:stone"}), joint(point(0, 0, 0), point(3, 0, 0))]
            """), DraftKit.zone(4, 1, 1)));
        helper.assertValueEqual(loose.why(), DraftRefusal.PATTERN_FAILED, "a joint to nothing is refused");
        helper.succeed();
    }

    static Drawn.Ready drawn(GameTestHelper helper, BlockPos... marks) {
        Drawn drawn = lay(railway(), new DraftKit.Ground(0), marks);
        if (drawn instanceof Drawn.Refused refused) {
            helper.fail("expected a railway, got " + refused.why() + " " + refused.detail());
        }
        return (Drawn.Ready) drawn;
    }

    private static final List<List<BlockPos>> ROUTES = List.of(
        List.of(BlockPos.ZERO, new BlockPos(0, 0, 16), new BlockPos(16, 0, 16)),
        List.of(BlockPos.ZERO, new BlockPos(16, 0, 0), new BlockPos(28, 0, 12)),
        List.of(BlockPos.ZERO, new BlockPos(12, 0, 12), new BlockPos(12, 0, 28)),
        List.of(BlockPos.ZERO, new BlockPos(12, 0, 12), new BlockPos(0, 0, 24)),
        List.of(BlockPos.ZERO, new BlockPos(0, 3, 24)),
        List.of(BlockPos.ZERO, new BlockPos(24, -3, 0)),
        List.of(BlockPos.ZERO, new BlockPos(20, 2, 20)),
        List.of(BlockPos.ZERO, new BlockPos(0, 8, 64)),
        List.of(BlockPos.ZERO, new BlockPos(0, 0, 16), new BlockPos(36, 3, 16)));

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void everyTurnAndClimbIsOneCreateWouldLayAndRunOver(GameTestHelper helper) {
        var level = helper.getLevel();
        List<Drawn.Ready> drawings = new ArrayList<>();
        List<BlockPos> bases = new ArrayList<>();
        for (int index = 0; index < ROUTES.size(); index++) {
            Drawn.Ready ready = drawn(helper, ROUTES.get(index).toArray(BlockPos[]::new));
            helper.assertTrue(!ready.draft().joints().isEmpty(), "route " + index + " is joined somewhere");
            BlockPos base = helper.absolutePos(new BlockPos(index * 90 - 400, 70, -40));
            for (int cx = (base.getX() - 80) >> 4; cx <= (base.getX() + 80) >> 4; cx++) {
                for (int cz = (base.getZ() - 80) >> 4; cz <= (base.getZ() + 80) >> 4; cz++) {
                    level.setChunkForced(cx, cz, true);
                }
            }
            for (Draft.Cell cell : ready.draft().cells()) {
                if (track(cell.state())) {
                    level.setBlock(base.offset(ready.corner()).offset(cell.offset()), cell.state(), 3);
                }
            }
            drawings.add(ready);
            bases.add(base.offset(ready.corner()));
        }
        helper.runAfterDelay(5, () -> {
            for (int index = 0; index < drawings.size(); index++) {
                for (Joint joint : drawings.get(index).draft().joints()) {
                    BlockPos from = bases.get(index).offset(joint.from());
                    BlockPos to = bases.get(index).offset(joint.to());
                    var cost = Joints.joiner().cost(level, from, to);
                    helper.assertTrue(cost.isPresent() && !cost.get().isEmpty(),
                        "route " + index + ": Create lays the joint " + joint);
                    List<ItemStack> paid = new ArrayList<>(cost.get());
                    var joined = Joints.joiner().join(level, from, to, paid);
                    helper.assertTrue(joined.joined() && joined.left().isEmpty(),
                        "route " + index + ": joined for exactly its cost " + joint + " left " + joined.left());
                }
            }
            helper.runAfterDelay(10, () -> {
                for (int index = 0; index < drawings.size(); index++) {
                    BlockPos base = bases.get(index);
                    List<BlockPos> route = ROUTES.get(index);
                    BlockPos start = route.getFirst().offset(0, 1, 0);
                    BlockPos end = route.getLast().offset(0, 1, 0);
                    int holding = 0;
                    StringBuilder seen = new StringBuilder();
                    for (TrackGraph graph : Create.RAILWAYS.trackNetworks.values()) {
                        boolean first = false;
                        boolean last = false;
                        for (TrackNodeLocation node : graph.getNodes()) {
                            Vec3 at = node.getLocation().subtract(Vec3.atLowerCornerOf(base.subtract(drawings.get(index).corner())));
                            if (at.lengthSqr() < 90000) {
                                seen.append(graph.id.toString(), 0, 4).append(at).append(' ');
                            }
                            first |= at.distanceTo(Vec3.atBottomCenterOf(start)) < 1.1;
                            last |= at.distanceTo(Vec3.atBottomCenterOf(end)) < 1.1;
                        }
                        holding += first && last ? 1 : 0;
                    }
                    helper.assertValueEqual(holding, 1, "route " + index + " is one graph from its first mark to its last: " + seen);
                }
                for (BlockPos base : bases) {
                    for (int cx = (base.getX() - 80) >> 4; cx <= (base.getX() + 80) >> 4; cx++) {
                        for (int cz = (base.getZ() - 80) >> 4; cz <= (base.getZ() + 80) >> 4; cz++) {
                            level.setChunkForced(cx, cz, false);
                        }
                    }
                }
                helper.succeed();
            });
        });
    }
}
