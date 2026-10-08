package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.count;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.describe;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.stateAt;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftVocabularyGameTests {

    private static final String TEMPLATE = "empty";

    private DraftVocabularyGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftVocabularyGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void floatingSharesTileAnyWidthExactly(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("bay", bay, ["minecraft:cobblestone"]) for bay in split(site.zone, "x", ["~1"] * 3)]
            """);
        for (int width : new int[] {5, 7, 8, 11}) {
            helper.assertValueEqual(ready(helper, draw(pattern, zone(width, 1, 1))).cells().size(), width,
                "three bays tile a wall " + width + " wide with nothing left over");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void whatNoShareClaimsIsAnErrorUntilRestSaysWhereItGoes(GameTestHelper helper) {
        String source = """
            def draw(site):
                a, b = split(site.zone, "x", ["2", "2"]%s)
                return [part("a", a, ["minecraft:cobblestone"]), part("b", b, ["minecraft:oak_planks"])]
            """;
        Drawn.Refused refusal = refused(helper, draw(parse(source.formatted("")), zone(7, 1, 1)));
        helper.assertTrue(refusal.detail().contains("3 of 7 cells unassigned"), "names what was left, got: "
            + refusal.detail());
        Draft centred = ready(helper, draw(parse(source.formatted(", rest = 'center'")), zone(7, 1, 1)));
        helper.assertValueEqual(stateAt(centred, 0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "a centred split starts where the leftover's first half ends");
        helper.assertValueEqual(ready(helper, draw(parse(source.formatted(", rest = 'center'")), zone(7, 1, 1)))
            .extent().size().getX(), 4, "and covers only what the shares claim");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aSymmetricSplitMirrorsItsHalves(GameTestHelper helper) {
        String source = """
            def draw(site):
                sizes = [s.width for s in split(site.zone, "x", ["~1", "~1", "~1"]%s)]
                fail(str(sizes))
            """;
        helper.assertTrue(refused(helper, draw(parse(source.formatted("")), zone(8, 1, 1))).detail()
            .contains("[3, 3, 2]"), "by weight, the earliest share takes the odd cell");
        helper.assertTrue(refused(helper, draw(parse(source.formatted(", symmetric = True")), zone(8, 1, 1))).detail()
            .contains("[3, 2, 3]"), "symmetric, the outer pair take them together");
        Drawn.Refused odd = refused(helper, draw(parse("""
            def draw(site):
                return [part("x", s, ["minecraft:cobblestone"]) for s in split(site.zone, "x", ["~1", "~1"], symmetric = True)]
            """), zone(7, 1, 1)));
        helper.assertTrue(odd.detail().contains("cannot share evenly"), "an odd cell with no middle share, got: "
            + odd.detail());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPieceShareTakesItsOwnWidthAndRepeatTilesWholeUnits(GameTestHelper helper) {
        Pattern pattern = parse("""
            DOOR = piece(key = {"d": "minecraft:oak_planks"}, layers = [["ddd"]])

            def draw(site):
                around = [s.width for s in split(site.zone, "x", ["~1", DOOR, "~1"])]
                tiles = [s.width for s in split(site.zone, "x", ["~4*"])]
                spaced = [(s.name, s.width) for s in split(site.zone, "x", [repeat(DOOR, gap = "~1")])]
                fail("%s %s %s" % (around, tiles, spaced))
            """);
        String said = refused(helper, draw(pattern, zone(10, 1, 1))).detail();
        helper.assertTrue(said.contains("[3, 3, 4]") || said.contains("[4, 3, 3]") || said.contains("[3, 4, 3]"),
            "a door's three cells between two floating shares, got: " + said);
        helper.assertTrue(said.contains("[3, 4, 3]"), "three whole tiles near four cells, the extra in the middle,"
            + " got: " + said);
        helper.assertTrue(said.contains("(\"gap\", 1), (\"\", 3), (\"gap\", 2), (\"\", 3), (\"gap\", 1)"),
            "two whole doors with the gaps spread between, got: " + said);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void namesAndFacesSurviveASplit(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                front = comp(site.zone, "front")[0]
                left, right = split(front, "x", ["~1", "~1"], names = ["door", ""])
                fail("%s %s %s %s" % (left.name, left.face, right.name, right.face))
            """);
        helper.assertTrue(refused(helper, draw(pattern, zone(4, 2, 4))).detail().contains("door front front front"),
            "a named share and an unnamed one both still know their face");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void everyFaceCarriesItsOwnFrame(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part(face.face, face.sized(width = 1, height = 1, depth = 1), ["minecraft:cobblestone"])
                        for face in comp(site.zone)]
            """);
        Draft draft = ready(helper, draw(pattern, zone(3, 1, 3)));
        helper.assertValueEqual(draft.cells().size(), 4, "one cell per side");
        for (BlockPos corner : List.of(new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), new BlockPos(0, 0, 2),
                new BlockPos(2, 0, 2))) {
            helper.assertTrue(stateAt(draft, corner.getX(), corner.getY(), corner.getZ()) != null,
                "a side's own origin at " + corner.toShortString());
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void edgesAndCornersAreTheirOwnScopes(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                edges = comp(site.zone, "edges")
                corners = comp(site.zone, "corners")
                if len(edges) != 12 or len(corners) != 8:
                    fail("%d edges, %d corners" % (len(edges), len(corners)))
                return ([part("post", e, ["minecraft:oak_planks"]) for e in edges if e.name.count("-") == 1 and
                         e.name.startswith("front") or e.name.startswith("back")] +
                        [part("knob", c, ["minecraft:cobblestone"]) for c in corners if c.name.startswith("top")])
            """);
        Draft draft = ready(helper, draw(pattern, zone(3, 4, 3)));
        helper.assertValueEqual(count(draft, Blocks.OAK_PLANKS), 12, "four vertical posts, three tall below the knobs");
        helper.assertValueEqual(count(draft, Blocks.COBBLESTONE), 4, "a knob on each top corner");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void centringOnAnEvenSpareAsksWhichSideTakesTheCell(GameTestHelper helper) {
        String source = """
            def draw(site):
                return [part("slot", site.zone.sized(width = 2, anchor = "center"%s), ["minecraft:cobblestone"])]
            """;
        helper.assertTrue(refused(helper, draw(parse(source.formatted("")), zone(5, 1, 1))).detail()
            .contains("parity"), "the refusal names parity");
        Draft low = ready(helper, draw(parse(source.formatted(", parity = 'high'")), zone(5, 1, 1)));
        helper.assertValueEqual(((Drawn.Ready) draw(parse(source.formatted(", parity = 'high'")), zone(5, 1, 1)))
            .corner().getX(), 2, "the high side takes the spare cell");
        helper.assertValueEqual(low.cells().size(), 2, "two cells wide");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aMirroredScopeMirrorsWhatIsStampedIntoIt(GameTestHelper helper) {
        String source = """
            STEP = piece(key = {"s": "minecraft:oak_stairs[facing=east]", "#": "minecraft:cobblestone"}, layers = [["s#"]])
            def draw(site):
                return [stamp("step", STEP, site.zone%s)]
            """;
        Draft plain = ready(helper, draw(parse(source.formatted("")), zone(2, 1, 1)));
        Draft mirrored = ready(helper, draw(parse(source.formatted(".mirror()")), zone(2, 1, 1)));
        helper.assertValueEqual(facing(stateAt(plain, 0, 0, 0)), Direction.EAST, "as written, on the left");
        helper.assertValueEqual(facing(stateAt(mirrored, 1, 0, 0)), Direction.WEST,
            "mirrored, it swaps sides and turns to face the other way");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void hipAndShedRoofsClimbWhereTheySay(GameTestHelper helper) {
        Draft hip = ready(helper, draw(parse("""
            def draw(site):
                return [part("roof", hip(site.zone), ["minecraft:oak_planks"], fit = "stepped")]
            """), zone(5, 4, 5)));
        helper.assertValueEqual(DraftKit.column(hip, 2, 2).get(0).offset().getY(), 2, "the middle is the peak");
        helper.assertValueEqual(DraftKit.column(hip, 0, 2).get(0).offset().getY(), 0, "every eave is low");
        helper.assertValueEqual(DraftKit.column(hip, 2, 0).get(0).offset().getY(), 0, "on all four sides");

        Draft shed = ready(helper, draw(parse("""
            def draw(site):
                return [part("roof", shed(site.zone), ["minecraft:oak_planks"], fit = "stepped")]
            """), zone(1, 5, 5)));
        helper.assertValueEqual(DraftKit.column(shed, 0, 0).get(0).offset().getY(), 0,
            "lowest at the front, the north edge");
        helper.assertValueEqual(DraftKit.column(shed, 0, 4).get(0).offset().getY(), 4, "highest at the back");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void roundShapesAndFloorPlansFillWhatTheyOutline(GameTestHelper helper) {
        Draft round = ready(helper, draw(parse("""
            def draw(site):
                return [part("tower", cylinder(site.zone), ["minecraft:cobblestone"])]
            """), zone(5, 1, 5)));
        helper.assertTrue(stateAt(round, 2, 0, 2) != null, "the middle is inside");
        helper.assertValueEqual(round.extent().size().getX(), 5, "and it reaches its box's sides");
        helper.assertValueEqual(round.cells().stream().filter(cell -> cell.offset().getX() == 0).count(), 3L,
            "but its edge is round: three cells, not five");

        Draft ell = ready(helper, draw(parse("""
            def draw(site):
                outline = [[0, 0], [4, 0], [4, 2], [2, 2], [2, 4], [0, 4]]
                return [part("wing", extrude(site.zone, outline), ["minecraft:cobblestone"])]
            """), zone(4, 1, 4)));
        helper.assertValueEqual(ell.cells().size(), 12, "an L of three two-by-two squares");

        Draft ring = ready(helper, draw(parse("""
            def draw(site):
                middle = site.zone.inset(x = 1, z = 1)
                return [part("ring", subtract(site.zone, middle), ["minecraft:cobblestone"]),
                        part("cross", intersect(site.zone.inset(x = 1), site.zone.inset(z = 1)), ["minecraft:oak_planks"],
                             over = "empty")]
            """), zone(3, 1, 3)));
        helper.assertValueEqual(count(ring, Blocks.COBBLESTONE), 8, "a box less its middle");
        helper.assertValueEqual(count(ring, Blocks.OAK_PLANKS), 1, "what two strips share, only where it was empty");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void underARoofFillsTheGableEnds(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                roof = gable(site.zone)
                ends = group(comp(site.zone, "left") + comp(site.zone, "right"))
                return [part("roof", roof, ["minecraft:oak_planks"]),
                        part("gable", intersect(under(roof, site.zone), ends), ["minecraft:cobblestone"])]
            """), zone(3, 3, 5)));
        helper.assertValueEqual(count(draft, Blocks.COBBLESTONE), 8, "a triangle of four under each end");
        helper.assertTrue(stateAt(draft, 1, 1, 2) == null, "nothing between the ends");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aSweepFollowsItsLineRoundACorner(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                line = [point(0, 0, 0), point(4, 0, 0), point(4, 0, 4)]
                return [part("wall", sweep(line), ["minecraft:cobblestone"])]
            """), zone(1, 1, 1)));
        helper.assertValueEqual(draft.cells().size(), 9, "five east, then four more south");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStampedPieceTurnsWithTheWallItIsLaidOn(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            DOOR = piece(key = {"d": "minecraft:oak_door[facing=north,half=lower]"}, layers = [["d"]])
            def draw(site):
                return [stamp(face.face, DOOR, face.sized(width = 1, height = 1, depth = 1)) for face in comp(site.zone)]
            """), zone(3, 1, 3)));
        Set<Direction> facings = new HashSet<>();
        for (Draft.Cell cell : draft.cells()) {
            facings.add(facing(cell.state()));
        }
        helper.assertValueEqual(facings.size(), 4, "one door per wall, each facing out of its own");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aSwapKeepsWhatTheShapeSaid(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            STEP = piece(key = {"s": "minecraft:oak_stairs[facing=north,half=bottom]"}, layers = [["s"]])
            def draw(site):
                return [stamp("step", STEP, site.zone.sized(width = 1, height = 1, depth = 1),
                              swap = {"minecraft:oak_stairs": ["minecraft:stone_brick_stairs"]})]
            """), zone(2, 2, 2)));
        BlockState step = draft.cells().get(0).state();
        helper.assertValueEqual(step.getBlock(), Blocks.STONE_BRICK_STAIRS, "the palette it swapped to");
        helper.assertValueEqual(facing(step), Direction.NORTH, "the way the written state faced");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStretchedPieceGrowsOnlyWhereItSays(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            WINDOW = piece(key = {"#": "minecraft:oak_planks", "o": "minecraft:glass_pane"},
                           layers = [["#o#"], ["#o#"]], stretch = {"x": [1]})
            def draw(site):
                return [stamp("window", WINDOW, site.zone, fit = "stretch")]
            """), zone(6, 2, 1)));
        helper.assertValueEqual(count(draft, Blocks.GLASS_PANE), 8, "four panes across, two high");
        helper.assertValueEqual(count(draft, Blocks.OAK_PLANKS), 4, "the frame stays one cell each side");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void variantsLayTheLargestThatFitsAndOverflowIsAnError(GameTestHelper helper) {
        String source = """
            SMALL = piece(key = {"s": "minecraft:cobblestone"}, layers = [["s"]])
            LARGE = piece(key = {"l": "minecraft:oak_planks"}, layers = [["lll"]])
            def draw(site):
                return [stamp("sign", %s, site.zone%s)]
            """;
        helper.assertValueEqual(count(ready(helper, draw(parse(source.formatted("variants([SMALL, LARGE])", "")),
            zone(3, 1, 1))), Blocks.OAK_PLANKS), 3, "the large one where it fits");
        helper.assertValueEqual(count(ready(helper, draw(parse(source.formatted("variants([SMALL, LARGE])", "")),
            zone(2, 1, 1))), Blocks.COBBLESTONE), 1, "the small one where it does not");
        helper.assertTrue(refused(helper, draw(parse(source.formatted("LARGE", "")), zone(2, 1, 1))).detail()
            .contains("does not fit"), "a piece too large for its scope is an error");
        helper.assertValueEqual(count(ready(helper, draw(parse(source.formatted("LARGE", ", overflow = 'clip'")),
            zone(2, 1, 1))), Blocks.OAK_PLANKS), 2, "unless it may be clipped");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aMixUsesEveryBlockTheSameWayEachTime(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("wall", site.zone, mix({"minecraft:cobblestone": 1, "minecraft:mossy_cobblestone": 1}))]
            """);
        Draft first = ready(helper, draw(pattern, zone(8, 1, 8)));
        helper.assertTrue(count(first, Blocks.COBBLESTONE) > 10 && count(first, Blocks.MOSSY_COBBLESTONE) > 10,
            "both blocks, near half each: " + count(first, Blocks.COBBLESTONE));
        helper.assertValueEqual(describe(first), describe(ready(helper, draw(pattern, zone(8, 1, 8)))),
            "and the same cells each time");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void logsLieAlongTheirPartAndFencesFillTheirCells(GameTestHelper helper) {
        Draft beam = ready(helper, draw(parse("""
            def draw(site):
                return [part("beam", site.zone, ["minecraft:oak_log"], orient = "along"),
                        part("rail", site.zone.at(y = 1), ["minecraft:oak_fence"])]
            """), zone(5, 1, 1)));
        helper.assertValueEqual(stateAt(beam, 2, 0, 0).getValue(BlockStateProperties.AXIS), Direction.Axis.X,
            "a beam five long lies east-west");
        helper.assertValueEqual(count(beam, Blocks.OAK_FENCE), 5, "a fence in every cell of its part");
        helper.succeed();
    }

    private static Direction facing(BlockState state) {
        return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }
}
