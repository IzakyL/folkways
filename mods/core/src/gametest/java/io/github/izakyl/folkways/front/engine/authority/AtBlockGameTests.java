package io.github.izakyl.folkways.front.engine.authority;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.testing.TestFrame;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AtBlockGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(AtBlockGameTests.class); }

    @GameTest(template = "empty")
    public static void packetReachFollowsTheVisibleRotatedStructureAndStillRejectsRemoteBlocks(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos storage = helper.absolutePos(new BlockPos(2, 1, 2));
        TestFrame frame = TestFrame.turned(level, storage, Vec3.atLowerCornerOf(storage.offset(1000, 20, -1000)));
        Runnable remove = frame.install(true);
        try {
            Vec3 visible = frame.toWorld(new Vec3(0.5, 0.5, 0.5));
            helper.assertTrue(AtBlock.nearby(level, visible.add(0, 0, 2), storage),
                "a real nearby ship block is not rejected for its distant storage address");
            helper.assertFalse(AtBlock.nearby(level, Vec3.atCenterOf(storage), storage),
                "standing near storage alone cannot authorize remote ship interaction");
            helper.assertTrue(AtBlock.nearby(level, visible.add(8, 0, 0), storage), "the eight-block reach limit remains inclusive");
            helper.assertFalse(AtBlock.nearby(level, visible.add(8.01, 0, 0), storage), "the same reach limit rejects remote visible blocks");
            frame.moveBy(new Vec3(30, 0, 0));
            helper.assertFalse(AtBlock.nearby(level, visible, storage), "moving the ship invalidates an old nearby position");
            helper.assertTrue(AtBlock.nearby(level, frame.toWorld(new Vec3(0.5, 0.5, 0.5)), storage),
                "reach follows its current pose");
        } finally { remove.run(); }
        helper.assertTrue(AtBlock.nearby(level, Vec3.atCenterOf(storage).add(2, 0, 0), storage), "ordinary blocks retain world reach");
        helper.assertFalse(AtBlock.nearby(level, Vec3.atCenterOf(storage).add(9, 0, 0), storage), "ordinary remote blocks stay refused");
        helper.succeed();
    }
}
