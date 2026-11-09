package io.github.izakyl.folkways.vanilla.build.draft;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.vanilla.FolkwaysVanillaMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class MineOneShotGameTests {
    private MineOneShotGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MineOneShotGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 24000)
    public static void maximalMine(GameTestHelper helper) {
        Pattern pattern = DraftKit.shipped("mine");
        Hint hint = new Hint.Zone(new BlockPos(0, 65, 0), new BlockPos(2, 65, 2));
        DraftKit.Filled settings = DraftKit.Filled.defaulting(pattern.knobs()).count("y", -64).count("spacing", 2);
        for (String direction : new String[] {"east", "south", "west", "north"}) {
            settings.count("main_" + direction, 128).count("branch_" + direction, 48);
        }
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).atY(65);
        Hint captureHint = new Hint.Zone(origin, origin.offset(2, 0, 2));
        Area area = pattern.area(captureHint);
        for (long chunk : area.chunks()) {
            helper.getLevel().getChunk(ChunkPos.getX(chunk), ChunkPos.getZ(chunk));
        }
        for (int x = -5; x <= 6; x++) {
            for (int z = -5; z <= 6; z++) {
                helper.getLevel().setBlock(origin.offset(x, -1, z), Blocks.STONE.defaultBlockState(), 2);
            }
        }
        World snapshot = World.capture(helper.getLevel(), area);
        var drawing = Drafting.draw(pattern, new Commission(hint, settings, new DraftKit.Ground(64).floorAt(-64),
            Direction.NORTH, 7L));
        var capturedDrawing = Drafting.draw(pattern, new Commission(captureHint, settings, snapshot,
            Direction.NORTH, 7L));
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(drawing.isDone() && capturedDrawing.isDone(),
                "the mines finish drawing"))
            .thenExecute(() -> {
                Drawn drawn = drawing.join();
                Drawn capturedDrawn = capturedDrawing.join();
                helper.assertTrue(drawn instanceof Drawn.Ready, "maximum mine draws: " + drawn);
                helper.assertTrue(capturedDrawn instanceof Drawn.Ready, "captured mine draws: " + capturedDrawn);
            }).thenSucceed();
    }
}
