package io.github.izakyl.folkways.war;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysWarMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class RaidRegressionGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(RaidRegressionGameTests.class); }

    @GameTest(batch = "war", template = "empty_large")
    public static void completedBreachSurvivesTicksBeforeTheSelectorStopsIt(GameTestHelper helper) {
        var scene = new RaidGameTests.Scene(helper);
        for (int z = 1; z < 15; z++) { for (int y = 1; y < 5; y++) { helper.setBlock(new BlockPos(11, y, z), Blocks.STONE_BRICKS); } }
        scene.begin(RaidDefinition.parse(RaidGameTests.definition("zombie", 1).snapshot().replace("fighter", "breacher")));
        scene.director.advance(true);
        Mob mob = scene.director.loaded().getFirst(); mob.setNoAi(true);
        BlockPos near = helper.absolutePos(new BlockPos(9, 1, 8)); mob.moveTo(near.getX() + .5, near.getY(), near.getZ() + .5);
        var goal = new WrappedGoal(1, new RaidBreachGoal(mob));
        helper.assertTrue(goal.canUse(), "the real wall provides a breach target"); goal.start();
        for (int tick = 0; tick < 120; tick++) { goal.tick(); }
        helper.assertTrue(goal.isRunning(), "the selector has not yet called stop");
        helper.assertFalse(goal.canContinueToUse(), "a completed breach asks the selector to stop");
        goal.tick(); goal.tick();
        helper.assertValueEqual(scene.director.raid().broken, 1, "extra running ticks neither crash nor repeat the edit");
        goal.stop(); goal.tick();
        helper.assertFalse(goal.isRunning(), "the selector owns the final stop");
        helper.succeed();
    }
}
