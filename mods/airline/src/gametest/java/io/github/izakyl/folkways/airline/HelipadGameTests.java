package io.github.izakyl.folkways.airline;

import net.minecraft.core.BlockPos;
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
public final class HelipadGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HelipadGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void whiteSlenderHelipadProportions(GameTestHelper helper) {
        var cells = new java.util.HashMap<BlockPos, Integer>();
        AirportSurface surface = new AirportSurface() {
            public int material(BlockPos pos) {
                return cells.getOrDefault(pos, OTHER);
            }

            public boolean loaded(BlockPos pos) {
                return true;
            }
        };
        for (int size : new int[] {7, 11, 15, 31}) {
            for (boolean rotated : new boolean[] {false, true}) {
                cells.clear();
                AirportMarkings.pad(cells, BlockPos.ZERO, size, rotated, false);
                helper.assertTrue(Helipads.atCenter(surface, BlockPos.ZERO).isPresent(), "slender white H " + size);
                cells.put(BlockPos.ZERO, AirportSurface.GRAY);
                helper.assertTrue(Helipads.atCenter(surface, BlockPos.ZERO).isEmpty(), "crossbar required");
            }
        }
        cells.clear();
        AirportMarkings.pad(cells, BlockPos.ZERO, 15, false, true);
        helper.assertTrue(Helipads.atCenter(surface, BlockPos.ZERO).isEmpty(), "old thick H rejected");
        helper.succeed();
    }
}
