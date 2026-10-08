package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.count;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.stateAt;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftContextGameTests {

    private static final String TEMPLATE = "empty";

    private DraftContextGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftContextGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aRuleMayCallItself(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                return Halve(site.zone, 3)

            def halve(site, scope, depth):
                if depth == 0:
                    return part("leaf", scope, ["minecraft:cobblestone"])
                left, right = split(scope, "x", ["~1", "~1"])
                return [Halve(left, depth - 1), Halve(right, depth - 1)]

            Halve = rule("halve", halve)
            """), zone(8, 1, 1)));
        helper.assertValueEqual(draft.cells().size(), 8, "three halvings of an eight-wide lot");
        helper.assertTrue(draft.cells().stream().anyMatch(cell -> cell.source().path()
            .equals("halve/halve[1]/halve/halve/leaf")), "each cell knows the rules it was drawn under, got "
            + draft.cells().get(7).source().path());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPhaseSeesWhatEarlierPhasesDrewAndNothingOfItsOwn(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            phases = ["mass", "detail"]

            def draw(site):
                return [Detail(site.zone), Mass(site.zone), Peek(site.zone)]

            def mass(site, scope):
                return part("mass", scope, ["minecraft:cobblestone"])

            def peek(site, scope):
                if tally("mass") != 0:
                    fail("a rule saw a part from its own phase")
                return []

            def detail(site, scope):
                if tally("mass") != 1 or not occluded(scope, by = "mass"):
                    fail("the detail phase did not see the mass")
                return part("trim", scope.sized(width = 1), ["minecraft:oak_planks"])

            Mass = rule("mass", mass, phase = "mass")
            Peek = rule("peek", peek, phase = "mass")
            Detail = rule("detail", detail, phase = "detail")
            """), zone(3, 1, 1)));
        helper.assertValueEqual(count(draft, Blocks.OAK_PLANKS), 1, "the later phase drew over the earlier");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aWallBesideAnotherWingKnowsItIsTouchingIt(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            phases = ["mass", "openings"]

            def draw(site):
                return [Wing(side, label = "mass") for side in split(site.zone, "x", ["~1", "~1"])]

            def wing(site, scope):
                return [part("wall", hollow(scope), ["minecraft:cobblestone"])] + [Window(face) for face in comp(scope)]

            def window(site, face):
                if touches(face, by = "mass"):
                    return []
                return part("window", face.sized(width = 1, anchor = "center"), ["minecraft:glass"])

            Wing = rule("wing", wing, phase = "mass")
            Window = rule("window", window, phase = "openings")
            """), zone(6, 1, 3)));
        helper.assertValueEqual(count(draft, Blocks.GLASS), 6,
            "a window in every wall but the two where the wings meet, each ignoring its own wing");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aFailingRuleSaysWhereInTheDrawingItWas(GameTestHelper helper) {
        Drawn.Refused refusal = refused(helper, draw(parse("""
            def draw(site):
                return [Wing(side, name = "wing") for side in split(site.zone, "x", ["~1", "~1"])]

            def wing(site, scope):
                if scope.origin.x > 0:
                    fail("no room on this side")
                return []

            Wing = rule("wing", wing)
            """), zone(4, 1, 1)));
        helper.assertTrue(refusal.detail().startsWith("wing[1]: "), "the path of the failing rule, got: "
            + refusal.detail());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aSplitSnapsToCutsAnEarlierPhaseLeft(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            phases = ["main", "annex"]

            def draw(site):
                main, annex = split(site.zone, "x", ["4", "~1"])
                floors = split(main, "y", ["~1", "~1"], emit = "floor")
                return [part("main", floors[0], ["minecraft:cobblestone"]), Annex(annex)]

            def annex(site, scope):
                low, high = split(scope, "y", ["~2", "~1"], snap = "floor", tolerance = 1)
                return part("annex", low, ["minecraft:oak_planks"])

            Annex = rule("annex", annex, phase = "annex")
            """), zone(6, 6, 1)));
        helper.assertValueEqual(DraftKit.column(draft, 5, 0).size(), 3,
            "the annex's lower floor pulled from four cells down to the main building's three");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aRegionExtendsDownToTheGroundOrStopsTheDrawing(GameTestHelper helper) {
        String source = """
            def draw(site):
                return [part("pier", extend(site.zone.at(y = 6), max = %d), ["minecraft:cobblestone"])]
            """;
        DraftKit.Ground ground = new DraftKit.Ground(0);
        Draft pier = ready(helper, draw(parse(source.formatted(32)), zone(1, 1, 1), new DraftKit.Filled(), ground));
        helper.assertValueEqual(pier.cells().size(), 6, "from six up down to the cell above the ground");
        Drawn.Refused refusal = refused(helper, draw(parse(source.formatted(3)), zone(1, 1, 1),
            new DraftKit.Filled(), ground));
        helper.assertValueEqual(refusal.why(), DraftRefusal.NO_GROUND, "a pier that cannot reach the ground");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aScopeSettlesOnTheGroundItsColumnsSurvey(GameTestHelper helper) {
        DraftKit.Ground ground = new DraftKit.Ground(2).column(0, 0, 5).column(1, 0, 4)
            .water(at -> at.getX() == 2 && at.getY() <= 3);
        Pattern pattern = parse("""
            def draw(site):
                under = site.survey(site.zone)
                if (under.min, under.median, under.max) != (2, 2, 5):
                    fail("surveyed %s %s %s" % (under.min, under.median, under.max))
                if under.water < 0.3 or under.water > 0.4:
                    fail("water %s" % under.water)
                return [part("pad", site.settle(site.zone, at = "max"), ["minecraft:cobblestone"])]
            """);
        Drawn drawn = draw(pattern, zone(3, 1, 3), new DraftKit.Filled(), ground);
        ready(helper, drawn);
        helper.assertValueEqual(((Drawn.Ready) drawn).corner().getY(), 6, "one cell above the highest ground");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPathConformsToTheSlopeItCrosses(GameTestHelper helper) {
        DraftKit.Ground slope = new DraftKit.Ground(0);
        for (int x = 0; x < 8; x++) {
            slope.column(x, 0, x / 2);
        }
        String source = """
            def draw(site):
                return [part("path", conform(site.zone%s), ["minecraft:oak_planks", "minecraft:oak_slab"],
                             fit = "stepped")]
            """;
        Draft stepped = ready(helper, draw(parse(source.formatted("")), zone(8, 1, 1), new DraftKit.Filled(), slope));
        helper.assertValueEqual(stateAt(stepped, 7, 3, 0), Blocks.OAK_PLANKS.defaultBlockState(),
            "each column one above its own ground");
        Draft smooth = ready(helper, draw(parse(source.formatted(", smooth = True")), zone(8, 1, 1),
            new DraftKit.Filled(), slope));
        helper.assertTrue(smooth.cells().stream().anyMatch(cell -> cell.state().getBlock() instanceof SlabBlock),
            "read smoothly, the slope comes out with half blocks: " + DraftKit.describe(smooth));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void digsTakeOnlyWhatIsThereAndUnloadedGroundIsRefused(GameTestHelper helper) {
        DraftKit.Ground hill = new DraftKit.Ground(-1).column(1, 1, 1);
        Pattern pattern = parse("""
            def draw(site):
                dig = terrain(site.zone)
                return [clear("dig", dig)] if dig else []
            """);
        Draft dug = ready(helper, draw(pattern, zone(3, 3, 3), new DraftKit.Filled(), hill));
        helper.assertValueEqual(dug.cells().size(), 2, "the hill's two cells inside the zone, not the air");
        helper.assertValueEqual(refused(helper, draw(pattern, zone(3, 3, 3), new DraftKit.Filled(),
            new DraftKit.Ground(0).unload(2, 2))).why(), DraftRefusal.UNLOADED, "a chunk that is not there");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPathArrivesFromItsFirstPointAndSpacesWhatIsLaidAlongIt(GameTestHelper helper) {
        Pattern pattern = parse("""
            accepts = ["path"]
            def draw(site):
                points = site.path.points
                if points[0] != point(0, 0, 0) or points[1] != point(10, 2, 0):
                    fail("points arrived as %s" % points)
                return [part("post", spot, ["minecraft:cobblestone"]) for spot in site.path.every(5)]
            """);
        Hint path = new Hint.Path(List.of(new BlockPos(20, 64, 20), new BlockPos(30, 66, 20)));
        Drawn drawn = pattern.drawOn(new Commission(path, new DraftKit.Filled(), 0L));
        Draft draft = ready(helper, drawn);
        helper.assertValueEqual(draft.cells().size(), 3, "a post at the start, the middle and the end");
        helper.assertValueEqual(((Drawn.Ready) drawn).corner(), new BlockPos(20, 64, 20), "the first point");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStretchOfAPathLiesAlongItHoweverItRuns(GameTestHelper helper) {
        Pattern pattern = parse("""
            accepts = ["path"]
            def draw(site):
                runs = site.path.stretches
                if len(runs) != 2:
                    fail("a line of three points has two stretches, got %d" % len(runs))
                if not runs[0].square or runs[0].depth != 5 or runs[0].facing != "south":
                    fail("the stretch straight down the world came out as %s" % runs[0])
                if runs[1].square or runs[1].depth != 5:
                    fail("the stretch across it came out as %s" % runs[1])
                return [part("run", runs[1].sized(width = 3, anchor = "center", parity = "low"),
                             ["minecraft:cobblestone"])]
            """);
        Hint path = new Hint.Path(List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 4), new BlockPos(3, 64, 7)));
        Drawn drawn = pattern.drawOn(new Commission(path, new DraftKit.Filled(), 0L));
        Draft draft = ready(helper, drawn);
        BlockPos corner = ((Drawn.Ready) drawn).corner();

        helper.assertTrue(at(draft, corner, 0, 64, 4) != null, "the band starts on the point the stretch does");
        helper.assertTrue(at(draft, corner, 3, 64, 7) != null, "and ends on the one it runs to");
        helper.assertTrue(at(draft, corner, 3, 64, 4) == null,
            "a band along a cornerwise line is not the box around it");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void theGroundIsReadFromARealLevel(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Pattern pattern = parse("""
            def draw(site):
                below = site.probe(point(0, 3, 0), "down", until = "ground", max = 8)
                if below != 3:
                    fail("probed %s" % below)
                return [part("pier", extend(site.zone.at(y = 3), max = 8), ["minecraft:cobblestone"])]
            """);
        Drawn drawn = pattern.drawOn(new Commission(new Hint.Zone(base, base), new DraftKit.Filled(),
            World.of(helper.getLevel()), Direction.NORTH, 0L));
        helper.assertValueEqual(ready(helper, drawn).cells().size(), 3, "down to the stone placed in the level");
        helper.succeed();
    }

    private static BlockState at(Draft draft, BlockPos corner, int x, int y, int z) {
        return stateAt(draft, x - corner.getX(), y - corner.getY(), z - corner.getZ());
    }
}
