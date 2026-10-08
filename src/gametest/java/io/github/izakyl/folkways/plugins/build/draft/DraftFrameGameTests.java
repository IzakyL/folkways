package io.github.izakyl.folkways.plugins.build.draft;

import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.count;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.describe;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.parse;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.plugins.build.draft.DraftKit.zone;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
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
public final class DraftFrameGameTests {

    private static final String TEMPLATE = "empty";

    private DraftFrameGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DraftFrameGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aQuarterTurnLeavesTheRoomItCoversWhereItWas(GameTestHelper helper) {
        String source = """
            def draw(site):
                return [part("floor", site.zone%s, ["minecraft:cobblestone"])]
            """;
        Draft plain = ready(helper, draw(parse(source.formatted("")), zone(2, 1, 3)));
        Draft turned = ready(helper, draw(parse(source.formatted(".rotate(1)")), zone(2, 1, 3)));
        helper.assertValueEqual(offsets(turned), offsets(plain), "the same cells, read along other axes");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPivotTurnsTheRoomItCoversWithIt(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                return [part("line", site.zone.pivot(90), ["minecraft:cobblestone"])]
            """), zone(1, 1, 3)));
        helper.assertValueEqual(draft.cells().size(), 3, "three cells still: " + describe(draft));
        Set<Integer> alongX = new TreeSet<>();
        Set<Integer> alongZ = new TreeSet<>();
        for (Draft.Cell cell : draft.cells()) {
            alongX.add(cell.offset().getX());
            alongZ.add(cell.offset().getZ());
        }
        helper.assertValueEqual(alongX.size(), 3, "a line that ran along z now runs along x: " + describe(draft));
        helper.assertValueEqual(alongZ.size(), 1, "and no longer along z: " + describe(draft));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aScopeAtAnAngleDrawsOnTheSlant(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                slant = site.zone.pivot(45)
                if slant.square:
                    fail("a scope turned 45 degrees no longer runs along the world's own axes")
                return [part("line", slant, ["minecraft:cobblestone"])]
            """), zone(1, 1, 6)));
        Set<Integer> alongX = new TreeSet<>();
        Set<Integer> alongZ = new TreeSet<>();
        for (Draft.Cell cell : draft.cells()) {
            alongX.add(cell.offset().getX());
            alongZ.add(cell.offset().getZ());
        }
        if (alongX.size() < 3 || alongZ.size() < 3) {
            helper.fail("a line on the slant runs across both axes at once: " + describe(draft));
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aRoofOffersSlopesThatLeanTheWayItDoes(GameTestHelper helper) {
        ready(helper, draw(parse("""
            def draw(site):
                roof = gable(site.zone, ridge = "x", rise = 1, run = 1)
                slopes = comp(roof, "slopes")
                if len(slopes) != 2:
                    fail("a gable has two slopes, got %d" % len(slopes))
                for face in slopes:
                    if face.square:
                        fail("a slope lies on the slant, and this one is square to the world")
                    if face.tilt < 44.0 or face.tilt > 46.0:
                        fail("a 1:1 slope leans 45 degrees, not %s" % face.tilt)
                return [part("roof", roof, ["minecraft:oak_planks"], fit = "stepped")]
            """), zone(3, 1, 5)));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void whatIsSplitOnASlopeRunsUpIt(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                roof = gable(site.zone, ridge = "x", rise = 1, run = 1)
                low, high = split(comp(roof, "slopes")[0], "y", ["~1", "~1"])
                return [part("low", low, ["minecraft:cobblestone"]),
                        part("high", high, ["minecraft:oak_planks"])]
            """), zone(3, 1, 7)));
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (Draft.Cell cell : draft.cells()) {
            if (cell.state().is(Blocks.COBBLESTONE)) {
                highest = Math.max(highest, cell.offset().getY());
            }
            if (cell.state().is(Blocks.OAK_PLANKS)) {
                lowest = Math.min(lowest, cell.offset().getY());
            }
        }
        if (lowest == Integer.MAX_VALUE || highest == Integer.MIN_VALUE) {
            helper.fail("both halves of the slope were drawn: " + describe(draft));
        }
        if (lowest <= highest) {
            helper.fail("the far half of a slope stands above the near half, and here it did not: "
                + describe(draft));
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aStoodUpPlanOffersOneFacePerSide(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                plan = outline(site.zone, corners = [[0, 0], [8, 0], [8, 4], [4, 4], [4, 8], [0, 8]])
                wing = extrude(site.zone, plan, height = 1)
                faces = comp(wing, "sides")
                if len(faces) != 6:
                    fail("an L-shaped plan has six sides, got %d" % len(faces))
                return [part("wall", face, ["minecraft:cobblestone"]) for face in faces]
            """), zone(8, 1, 8)));
        if (count(draft, Blocks.COBBLESTONE) < 20) {
            helper.fail("every side of the plan should have been built: " + describe(draft));
        }
        if (DraftKit.stateAt(draft, 5, 0, 5) != null) {
            helper.fail("a side is a wall along an edge, not the whole plan filled in: " + describe(draft));
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aShapeRemembersTheWayItsScopeFaced(GameTestHelper helper) {
        ready(helper, draw(parse("""
            def draw(site):
                turned = site.zone.rotate(1)
                if box(turned).bounds.facing != turned.facing:
                    fail("a shape hands back the scope it was cut to, facing the way it did")
                return [part("floor", turned, ["minecraft:cobblestone"])]
            """), zone(2, 1, 3)));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPlanDrawnInCutsACourtyard(GameTestHelper helper) {
        Draft draft = ready(helper, draw(parse("""
            def draw(site):
                plan = outline(site.zone)
                ring = subtract(extrude(site.zone, plan), extrude(site.zone, plan.offset(-1)))
                return [part("ring", ring, ["minecraft:cobblestone"])]
            """), zone(5, 1, 5)));
        helper.assertValueEqual(draft.cells().size(), 16,
            "the border of a five by five, and nothing inside it: " + describe(draft));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 80)
    public static void aPlanDrawnInTooFarIsRefused(GameTestHelper helper) {
        DraftKit.refused(helper, draw(parse("""
            def draw(site):
                plan = outline(site.zone)
                return [part("floor", extrude(site.zone, plan.offset(-4)), ["minecraft:cobblestone"])]
            """), zone(5, 1, 5)));
        helper.succeed();
    }

    private static List<BlockPos> offsets(Draft draft) {
        return draft.cells().stream().map(Draft.Cell::offset)
            .sorted((one, other) -> Long.compare(one.asLong(), other.asLong())).toList();
    }
}
