package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ORIGIN;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.count;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.describe;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.laid;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.refused;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.stateAt;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.Schema;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftGameTests {

    private static final String TEMPLATE = "empty";

    private DraftGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aMarkedZoneReachesThePatternAsItsSuggestedBox(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                zone = site.zone
                if site.kind != "zone" or zone.width != 3 or zone.height != 4 or zone.depth != 5:
                    fail("the marked dimensions changed")
                return [part("box", zone, ["minecraft:cobblestone"])]
            """);
        BlockPos first = new BlockPos(12, 23, 34);
        BlockPos second = new BlockPos(10, 20, 30);
        Hint hint = new Hint.Zone(first, second);
        Drawn result = pattern.drawOn(new Commission(hint, new DraftKit.Filled(), 0L));
        Draft draft = ready(helper, result);
        helper.assertValueEqual(((Drawn.Ready) result).corner(), second, "normalized anchor");
        helper.assertValueEqual(draft.cells().size(), 60, "the full marked volume");
        helper.assertValueEqual(Patterns.seedOf(pattern, new Hint.Zone(first, second)),
            Patterns.seedOf(pattern, new Hint.Zone(second, first)), "marking order does not change the seed");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aDrawingMayReachPastWhatWasMarked(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("porch", site.zone.outset(x = 1, z = 1), ["minecraft:cobblestone"]),
                        part("mast", site.zone.sized(width = 1, depth = 1).at(y = 1), ["minecraft:oak_planks"])]
            """);
        Hint hint = new Hint.Zone(new BlockPos(10, 5, 10), new BlockPos(12, 5, 12));
        Drawn result = pattern.drawOn(new Commission(hint, new DraftKit.Filled(), 0L));
        Draft draft = ready(helper, result);
        helper.assertValueEqual(((Drawn.Ready) result).corner(), new BlockPos(9, 5, 9),
            "the drawing's corner moves out with what it drew");
        helper.assertValueEqual(count(draft, Blocks.COBBLESTONE), 25, "a porch one cell wider on every side");
        helper.assertValueEqual(draft.extent().size().getY(), 2, "and a mast above the marked height");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aDrawingLargerThanOnePatternMayFillIsRefused(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("wall", site.zone.outset(x = 100, z = 100, y = 1), ["minecraft:cobblestone"])]
            """);
        helper.assertValueEqual(refused(helper, draw(pattern, zone(3, 1, 3))).why(), DraftRefusal.SITE_TOO_LARGE,
            "the refusal");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void onlyTheCellsADrawingSetsCountAgainstItsSize(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("near", site.zone, ["minecraft:cobblestone"]),
                        part("far", site.zone.at(x = 300, y = 60, z = 300), ["minecraft:cobblestone"]),
                        part("line", site.zone.at(z = 5).outset(x = 150), ["minecraft:cobblestone"])]
            """);
        Draft draft = ready(helper, draw(pattern, zone(1, 1, 1)));
        helper.assertValueEqual(draft.cells().size(), 303, "two lone cells and a line, far apart in a large box");
        helper.succeed();
    }

    private static Solid gable(int rise, int run) {
        return new Solid.Gable(Frame.over(new BoundingBox(0, 5, 0, 0, 5, 4), Direction.NORTH, false),
            new Vec3i(1, 1, 5), 0, rise, run, 1);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aGableStepsOneCellPerColumn(GameTestHelper helper) {
        Draft draft = laid(helper, List.of(new Massing.Part("roof",
            gable(1, 1),
            new Skin.Fitted(List.of(ItemFilter.item(id("oak_planks")), ItemFilter.item(id("oak_slab"))), Fit.STEPPED))));
        int[] expected = {0, 1, 2, 1, 0};
        for (int z = 0; z < expected.length; z++) {
            List<Draft.Cell> column = DraftKit.column(draft, 0, z);
            helper.assertValueEqual(column.size(), 1, "one course of roof in column z=" + z);
            helper.assertValueEqual(column.get(0).offset().getY(), expected[z], "roof height at z=" + z);
            helper.assertValueEqual(column.get(0).state(), Blocks.OAK_PLANKS.defaultBlockState(),
                "a whole block at 1:1, at z=" + z);
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aShallowPitchIsHalvedRatherThanRounded(GameTestHelper helper) {
        Draft draft = laid(helper, List.of(new Massing.Part("roof",
            gable(1, 2),
            new Skin.Fitted(List.of(ItemFilter.item(id("oak_planks")), ItemFilter.item(id("oak_slab"))), Fit.STEPPED))));
        if (draft.cells().stream().noneMatch(cell -> cell.state().getBlock() instanceof SlabBlock)) {
            helper.fail("a 1:2 pitch put no half block anywhere: " + describe(draft));
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPitchHalfBlocksCannotLayIsRefused(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("roof", gable(site.zone, rise = 1, run = 3), ["minecraft:oak_slab"])]
            """);
        Drawn.Refused refusal = refused(helper, draw(pattern, zone(5, 3, 5)));
        helper.assertTrue(refusal.detail().contains("half blocks"), "says why, got: " + refusal.detail());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aClearedPartCutsTheOneDeclaredBeforeIt(GameTestHelper helper) {
        Draft draft = laid(helper, List.of(
            new Massing.Part("wall", new Solid.Cuboid(new BoundingBox(0, 0, 0, 2, 2, 2)),
                new Skin.Fitted(List.of(ItemFilter.item(id("cobblestone"))), Fit.SOLID)),
            new Massing.Part("doorway", new Solid.Cuboid(new BoundingBox(1, 0, 1, 1, 1, 1)), Skin.cleared())));
        helper.assertValueEqual(stateAt(draft, 1, 0, 1), Blocks.AIR.defaultBlockState(), "the doorway's own cell");
        helper.assertValueEqual(stateAt(draft, 0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "a cell the doorway does not reach");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternThatStopsSaysWhyInItsOwnWords(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                if site.zone.width < 5:
                    fail("this pattern needs five cells across")
                return []
            """);
        Drawn.Refused refusal = refused(helper, draw(pattern, zone(3, 3, 3)));
        helper.assertValueEqual(refusal.why(), DraftRefusal.PATTERN_FAILED, "the refusal");
        helper.assertTrue(refusal.detail().contains("five cells across"),
            "the author's own words reach the caller, got: " + refusal.detail());
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void anEmptiedSlotIsRefusedByTheNameOfItsPart(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [part("wall", site.zone.sized(width = 1, height = 1, depth = 1),
                             ["folkways:nothing_at_all"])]
            """);
        Drawn.Refused refusal = refused(helper, draw(pattern, zone(2, 2, 2)));
        helper.assertValueEqual(refusal.why(), DraftRefusal.NOTHING_TO_BUILD_WITH, "the refusal");
        helper.assertValueEqual(refusal.detail(), "wall", "the part that needs filling");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void oneSiteDrawsOneBuilding(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                pier = site.zone.sized(width = 1, height = 1, depth = 1).at(x = site.rand(4))
                block = site.pick({"minecraft:cobblestone": 1, "minecraft:oak_planks": 3})
                return [part("pier", pier, [block])]
            """);
        Commission commission = new Commission(zone(8, 2, 2), new DraftKit.Filled(), 4242L);
        Draft first = ready(helper, pattern.drawOn(commission));
        Draft again = ready(helper, pattern.drawOn(commission));
        helper.assertValueEqual(describe(first), describe(again), "the same site draws the same building");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternDeclaresItsKnobsBeforeItDraws(GameTestHelper helper) {
        Pattern pattern = parse("""
            knobs = [
                count("storeys", 1, 3, default = 1),
                items("walls", default = ["#minecraft:planks"]),
                choice("pitch", ["steep", "shallow"], default = "steep"),
            ]

            def draw(site):
                return [part("floor", site.zone, site.items("walls"))]
            """);
        helper.assertValueEqual(pattern.knobs().settings().size(), 3, "three knobs declared");
        helper.assertTrue(pattern.knobs().find("storeys").orElseThrow() instanceof Schema.Setting.Count,
            "storeys is a number the player dials");
        helper.assertTrue(pattern.knobs().find("walls").orElseThrow() instanceof Schema.Setting.Items,
            "walls is a slot the player fills");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternDrawsWhatItsKnobsSay(GameTestHelper helper) {
        Pattern pattern = parse("""
            knobs = [count("storeys", 1, 3, default = 1), choice("pitch", ["steep", "shallow"], default = "shallow")]

            def draw(site):
                if site.choice("pitch") != "shallow":
                    fail("a plain choice came back as " + site.choice("pitch"))
                storeys = site.count("storeys")
                courses = split(site.zone, "y", ["1"] * (storeys * 2), rest = "drop")
                return [part("floor", courses[storey * 2], ["minecraft:cobblestone"]) for storey in range(storeys)]
            """);
        DraftKit.Filled two = DraftKit.Filled.defaulting(pattern.knobs()).count("storeys", 2);
        Draft draft = ready(helper, draw(pattern, zone(2, 5, 2), two, World.NONE));
        helper.assertValueEqual(draft.cells().size(), 8, "two storeys of 2x2 floor");
        helper.assertValueEqual(stateAt(draft, 0, 2, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "the second storey's floor");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternLoadsWhatALibraryDefines(GameTestHelper helper) {
        Map<String, String> libraries = Map.of(
            "minecraft:lib/blocks", """
                STONE = ["minecraft:cobblestone"]
                """,
            "minecraft:lib/rooms", """
                load("minecraft:lib/blocks.star", "STONE")
                def room(scope):
                    return part("room", hollow(scope), STONE)
                """);
        Pattern pattern = DraftKit.parse("house", """
            load("minecraft:lib/rooms.star", "room")

            def draw(site):
                return [room(site.zone)]
            """, libraries);
        helper.assertValueEqual(count(ready(helper, draw(pattern, zone(3, 1, 3))), Blocks.COBBLESTONE), 8,
            "the loaded rule drew its hollow room from the loaded palette");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aLibraryLoadingItselfDoesNotLoad(GameTestHelper helper) {
        Map<String, String> libraries = Map.of(
            "minecraft:lib/a", "load(\"minecraft:lib/b\", \"B\")\nA = 1\n",
            "minecraft:lib/b", "load(\"minecraft:lib/a\", \"A\")\nB = 1\n");
        helper.assertTrue(Pattern.parse(id("looping"), """
                load("minecraft:lib/a", "A")
                def draw(site):
                    return []
                """, wanted -> java.util.Optional.ofNullable(libraries.get(wanted.toString()))).isEmpty(),
            "a load cycle leaves no pattern behind");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternRefusesAMarkItDoesNotDrawOn(GameTestHelper helper) {
        Pattern pattern = parse("""
            accepts = ["path"]
            def draw(site):
                return [part("line", sweep(site.path), ["minecraft:cobblestone"])]
            """);
        helper.assertTrue(pattern.accepts(new Hint.Path(List.of(ORIGIN, ORIGIN.east(3)))), "a path is welcome");
        helper.assertValueEqual(refused(helper, draw(pattern, zone(3, 1, 3))).why(), DraftRefusal.WRONG_MARK,
            "a zone is not");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPatternNamesWhatItNeedsThatThisGameHasNotLoaded(GameTestHelper helper) {
        Pattern pattern = parse("""
            knobs = [
                items("track", default = ["nomod:track"]),
                items("bed", default = ["minecraft:stone"]),
            ]
            def draw(site):
                return [blocks("track", {point(0, 0, 0): site.items("track")[0]})]
            """);
        helper.assertValueEqual(pattern.unloaded(), java.util.Set.of(ResourceLocation.parse("nomod:track")),
            "the missing default is found when the pattern is read");
        Drawn.Refused refused = refused(helper, draw(pattern, zone(1, 1, 1)));
        helper.assertValueEqual(refused.why(), DraftRefusal.UNKNOWN_BLOCK, "drawing on its default is refused");
        helper.assertValueEqual(refused.detail(), "nomod:track", "naming what is missing");
        Draft drawn = ready(helper, draw(pattern, zone(1, 1, 1), new DraftKit.Filled().items("track", "minecraft:rail"),
            World.NONE));
        helper.assertValueEqual(stateAt(drawn, 0, 0, 0).getBlock(), Blocks.RAIL, "a loaded track stands in for it");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aDrawingLayingABlockThisGameHasNotLoadedIsRefused(GameTestHelper helper) {
        Pattern pattern = parse("""
            def draw(site):
                return [blocks("odd", {point(0, 0, 0): "nomod:thing[facing=east]"})]
            """);
        Drawn.Refused refused = refused(helper, draw(pattern, zone(1, 1, 1)));
        helper.assertValueEqual(refused.why(), DraftRefusal.UNKNOWN_BLOCK, "the unknown block is named");
        helper.assertTrue(refused.detail().contains("nomod:thing"), "as " + refused.detail());
        helper.succeed();
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
