package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import static io.github.izakyl.folkways.vanilla.build.draft.DraftKit.draw;
import static io.github.izakyl.folkways.vanilla.build.draft.DraftKit.ready;
import static io.github.izakyl.folkways.vanilla.build.draft.DraftKit.refused;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class DockPatternGameTests {

    private static final String TEMPLATE = "empty";

    private DockPatternGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DockPatternGameTests.class);
    }

    private static Pattern dock() {
        return DraftKit.shipped("dock");
    }

    private static DraftKit.Ground lake(int bank) {
        DraftKit.Ground world = new DraftKit.Ground(-4).water(at -> at.getY() <= 0);
        for (int x = -24; x <= 24; x++) {
            for (int z = -30; z < 0; z++) {
                world.column(x, z, bank);
            }
        }
        return world;
    }

    private static Map<BlockPos, BlockState> laid(Drawn.Ready drawn) {
        Map<BlockPos, BlockState> cells = new HashMap<>();
        for (Draft.Cell cell : drawn.draft().cells()) {
            cells.put(drawn.corner().offset(cell.offset()), cell.state());
        }
        return cells;
    }

    private static Drawn.Ready drawnOver(GameTestHelper helper, DraftKit.Filled settings, int bank,
            BlockPos from, BlockPos to) {
        Drawn drawn = draw(dock(), new Hint.Path(List.of(from, to)), settings, lake(bank));
        ready(helper, drawn);
        return (Drawn.Ready) drawn;
    }

    private static DraftKit.Filled defaults() {
        return DraftKit.Filled.defaulting(dock().knobs());
    }

    private static long count(Map<BlockPos, BlockState> cells, Block block) {
        return cells.values().stream().filter(state -> state.is(block)).count();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void aDockStandsOnPilesFromTheShoreOutOverTheWater(GameTestHelper helper) {
        Map<BlockPos, BlockState> cells = laid(drawnOver(helper, defaults(), 1,
            new BlockPos(0, 2, -3), new BlockPos(0, 2, 20)));
        helper.assertValueEqual(cells.get(new BlockPos(0, 1, 10)), Blocks.SPRUCE_PLANKS.defaultBlockState(),
            "the deck lies one block above the water");
        helper.assertValueEqual(cells.get(new BlockPos(0, -3, 20)).getBlock(), Blocks.SPRUCE_LOG,
            "the head's piles reach down to the bed");
        helper.assertTrue(cells.keySet().stream().noneMatch(at -> at.getY() < -3), "the bed itself is left alone");
        helper.assertTrue(cells.get(new BlockPos(0, 1, -2)).is(Blocks.COBBLESTONE), "a stone landing on the shore");
        helper.assertTrue(count(cells, Blocks.SPRUCE_FENCE) > 20, "railings run along the dock");
        helper.assertTrue(count(cells, Blocks.LANTERN) >= 4, "lanterns light the dock");
        helper.assertTrue(cells.get(new BlockPos(0, 1, 21)).is(Blocks.LADDER), "a ladder climbs out of the water");
        helper.assertTrue(cells.keySet().stream().anyMatch(at -> at.getX() == -2 && at.getZ() == 20),
            "the T head reaches past the walkway");
        for (int x = -2; x <= 2; x++) {
            helper.assertTrue(!cells.containsKey(new BlockPos(x, 2, 20)) || !cells.get(new BlockPos(x, 2, 20))
                .is(Blocks.SPRUCE_FENCE), "the head's front edge is left open for boats and anglers");
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void aLowBeachGetsStepsAndAShelterGetsARoof(GameTestHelper helper) {
        DraftKit.Filled settings = defaults().flag("shelter", true).choice("head", "l").count("width", 5);
        Map<BlockPos, BlockState> cells = laid(drawnOver(helper, settings, 0,
            new BlockPos(0, 1, -3), new BlockPos(0, 1, 20)));
        helper.assertTrue(count(cells, Blocks.DARK_OAK_STAIRS) > 10, "a pitched roof over the shelter");
        helper.assertTrue(count(cells, Blocks.COBBLESTONE_SLAB) > 0, "half steps climb from the beach to the deck");
        helper.assertTrue(cells.keySet().stream().noneMatch(at -> at.getX() > 2 && at.getZ() > 5 && at.getY() < 5),
            "an L head reaches to one side only");
        helper.assertTrue(cells.keySet().stream().anyMatch(at -> at.getX() < -4 && at.getZ() > 15),
            "an L head reaches out to the walker's right");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void aSlantedPathIsSquaredToItsMainHeading(GameTestHelper helper) {
        Map<BlockPos, BlockState> cells = laid(drawnOver(helper, defaults().choice("head", "none"), 1,
            new BlockPos(0, 2, -3), new BlockPos(6, 2, 20)));
        long across = cells.keySet().stream().filter(at -> at.getY() == 1 && at.getZ() == 10).count();
        helper.assertValueEqual(across, 3L, "the walkway runs straight, three boards across");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, templateNamespace = "folkways_core", timeoutTicks = 80)
    public static void aDockRefusesDryLandAndBadPaths(GameTestHelper helper) {
        Pattern pattern = dock();
        DraftKit.Filled settings = defaults();
        Drawn dry = draw(pattern, new Hint.Path(List.of(BlockPos.ZERO, new BlockPos(0, 0, 20))), settings,
            new DraftKit.Ground(-1));
        helper.assertTrue(DraftKit.explanationKey(refused(helper, dry)).equals(DraftKit.key(pattern, "error.dock.end")),
            "dry land is refused");
        Drawn backwards = draw(pattern, new Hint.Path(List.of(new BlockPos(0, 2, 20), new BlockPos(0, 2, -3))),
            settings, lake(1));
        helper.assertTrue(DraftKit.explanationKey(refused(helper, backwards)).equals(DraftKit.key(pattern, "error.dock.start")),
            "a path from the water is refused");
        Drawn bent = draw(pattern, new Hint.Path(List.of(new BlockPos(0, 2, -3), new BlockPos(0, 2, 8),
            new BlockPos(6, 2, 16))), settings, lake(1));
        refused(helper, bent);
        Drawn stub = draw(pattern, new Hint.Path(List.of(new BlockPos(0, 2, -3), new BlockPos(0, 2, 3))),
            settings, lake(1));
        helper.assertTrue(DraftKit.explanationKey(refused(helper, stub)).equals(DraftKit.key(pattern, "error.dock.reach")),
            "a stub is refused");
        Drawn cliff = draw(pattern, new Hint.Path(List.of(new BlockPos(0, 8, -3), new BlockPos(0, 8, 20))),
            settings, lake(7));
        helper.assertTrue(DraftKit.explanationKey(refused(helper, cliff)).equals(DraftKit.key(pattern, "error.dock.bank")),
            "a cliff is refused");
        helper.succeed();
    }
}
