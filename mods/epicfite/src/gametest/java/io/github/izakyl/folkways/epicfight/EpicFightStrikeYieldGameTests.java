package io.github.izakyl.folkways.epicfight;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.*;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.*;

@EventBusSubscriber(modid = FolkwaysEpicFight.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class EpicFightStrikeYieldGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(EpicFightStrikeYieldGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void upstreamStrikeNodeDrivesNativeCollisionAndReleasesOnce(GameTestHelper helper) {
        EpicFightTestScene s = new EpicFightTestScene(helper);
        s.zombie.setHealth(1);
        Node node = EpicFightNodeFixture.strike(s.level, s.zombie);
        Workload.Continuous workload = (Workload.Continuous) node.spec().workload();
        helper.assertTrue(workload.holds(s.level, s.worker), "upstream continuous workload opens real EF loan");
        helper.onEachTick(() -> { if (!s.closed && s.zombie.isAlive()) workload.holds(s.level, s.worker); });
        helper.startSequence().thenWaitUntil(() -> helper.assertFalse(s.zombie.isAlive(), "upstream node advances native collision to kill"))
            .thenExecute(() -> {
                helper.assertFalse(workload.holds(s.level, s.worker), "dead target ends upstream workload");
                node.released(s.level, s.worker, Ending.DONE);
                node.released(s.level, s.worker, Ending.DONE);
                helper.assertFalse(s.resident.hasWeaponSession(), "release removes the real hand owner");
                helper.assertValueEqual(s.resident.pack().count(Items.IRON_SWORD), 1, "repeated release returns exactly one sword");
            }).thenSucceed();
    }
}
