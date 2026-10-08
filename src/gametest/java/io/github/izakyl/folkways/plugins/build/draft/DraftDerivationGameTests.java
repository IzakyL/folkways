package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.describe;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.stateAt;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DraftDerivationGameTests {

    private static final String TEMPLATE = "empty";

    private DraftDerivationGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftDerivationGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void anAttributeReachesEveryRuleBelowTheOneThatSetIt(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                return [Wing(site.zone)]

            def wing(site, scope):
                return [Inner(scope)]

            def inner(site, scope):
                if attr("missing", default = "fallback") != "fallback":
                    fail("an attribute nobody set answers its fallback")
                blocks = ["minecraft:cobblestone"] if attr("kind") == "stone" else ["minecraft:oak_planks"]
                return [part("mark", scope, blocks)]

            Inner = rule("inner", inner)
            Wing = rule("wing", wing, attrs = {"kind": "stone"})
            """), zone(1, 1, 1)));
        helper.assertValueEqual(stateAt(draft, 0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "what the rule above handed down reached two rules deep");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aCallCanHandDownMoreThanTheRuleDoes(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                return [Wing(site.zone, attrs = {"kind": "wood"})]

            def wing(site, scope):
                blocks = ["minecraft:oak_planks"] if attr("kind") == "wood" else ["minecraft:cobblestone"]
                return [part("mark", scope, blocks)]

            Wing = rule("wing", wing, attrs = {"kind": "stone"})
            """), zone(1, 1, 1)));
        helper.assertValueEqual(stateAt(draft, 0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(),
            "what the call said wins over what the rule said");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void priorityDecidesWhichRuleGoesFirstWithinAPhase(GameTestHelper helper) {
        String source = """
            def draw(site):
                return [Late(site.zone), Early(site.zone)]

            def late(site, scope):
                return [part("late", scope, ["minecraft:oak_planks"])]

            def early(site, scope):
                return [part("early", scope, ["minecraft:cobblestone"])]

            Early = rule("early", early%s)
            Late = rule("late", late)
            """;
        Draft byOrder = ready(helper, draw(parse(source.formatted("")), zone(1, 1, 1)));
        helper.assertValueEqual(stateAt(byOrder, 0, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "with nothing to say otherwise, the rule drawn last lays the last cell");
        Draft byPriority = ready(helper, draw(parse(source.formatted(", priority = 10")), zone(1, 1, 1)));
        helper.assertValueEqual(stateAt(byPriority, 0, 0, 0), Blocks.OAK_PLANKS.defaultBlockState(),
            "a higher priority goes first, so what follows lays over it");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aDrawingHandsBackWhatItCountedUp(GameTestHelper helper) {
        Drawn drawn = draw(parse("""
            def draw(site):
                report("rooms")
                report("rooms")
                report("glass", 4)
                report("style", "cottage")
                return [part("floor", site.zone, ["minecraft:cobblestone"])]
            """), zone(1, 1, 1));
        ready(helper, drawn);
        Drawn.Ready ready = (Drawn.Ready) drawn;
        helper.assertValueEqual(ready.reports().get("rooms"), 2.0D, "two rooms counted");
        helper.assertValueEqual(ready.reports().get("glass"), 4.0D, "four cells of glass asked for");
        helper.assertValueEqual(ready.reports().get("style=cottage"), 1.0D, "what is not a number is counted");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aRulesDiceDoNotMoveWhenAnotherRuleIsAdded(GameTestHelper helper) {
        String source = """
            def draw(site):
                bays = split(site.zone, "x", ["~1"] * 3)
                return [%s Spot(bays[2], name = "right")]

            def spot(site, scope):
                return [part("mark", scope.sized(height = 1 + site.rand(4)), ["minecraft:cobblestone"])]

            Spot = rule("spot", spot)
            """;
        Draft alone = ready(helper, draw(parse(source.formatted("")), zone(3, 4, 1)));
        Draft crowded = ready(helper, draw(parse(source.formatted("Spot(bays[0], name = \"left\"),")),
            zone(3, 4, 1)));
        int by = alone.cells().size();
        int with = DraftKit.column(crowded, 2, 0).size();
        helper.assertValueEqual(with, by, "the right bay rolled the same either way: " + describe(crowded));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPartHandsBackItsRegionToGoOnBuildingFrom(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                walls = part("wall", hollow(site.zone), ["minecraft:cobblestone"])
                return [walls] + [part("cap", face, ["minecraft:oak_planks"])
                                  for face in comp(walls.region, "top")]
            """), zone(3, 2, 3)));
        helper.assertValueEqual(stateAt(draft, 0, 1, 0), Blocks.OAK_PLANKS.defaultBlockState(),
            "the top of what the wall covers was built on: " + describe(draft));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aTrimAnswersNoneWhereNothingIsShared(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                left, right = split(site.zone, "x", ["~1", "~1"])
                if trim(box(left), to = right) != None:
                    fail("two halves of a scope share no cell")
                return [part("kept", trim(box(site.zone), to = left), ["minecraft:cobblestone"])]
            """), zone(4, 1, 1)));
        helper.assertValueEqual(draft.cells().size(), 2, "only the half that was kept: " + describe(draft));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPieceShippedAsAStructureFileCanBeStamped(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            DOOR = template("folkways:door")

            def draw(site):
                return [stamp("door", DOOR, site.zone, fit = "exact")]
            """), zone(3, 2, 1)));
        helper.assertValueEqual(DraftKit.count(draft, Blocks.OAK_DOOR), 2, "both halves of the door");
        helper.assertValueEqual(DraftKit.count(draft, Blocks.OAK_PLANKS), 4, "and the frame around it");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStampCanTileAScopeItDoesNotFill(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            STRIPE = piece(key = {"#": "minecraft:cobblestone", ".": None}, layers = [["#."]])

            def draw(site):
                return [stamp("stripes", STRIPE, site.zone, fit = "tile")]
            """), zone(5, 1, 1)));
        helper.assertValueEqual(draft.cells().size(), 3, "every other cell, all the way along: " + describe(draft));
        helper.assertValueEqual(stateAt(draft, 2, 0, 0), Blocks.COBBLESTONE.defaultBlockState(),
            "and the pattern repeats: " + describe(draft));
        helper.succeed();
    }
}
