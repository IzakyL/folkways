package io.github.izakyl.folkways.plugins.person;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ResidentSwingGameTests {

    private static final String TEMPLATE = "empty";
    // A couple of ticks past the swing, so the check never races its last tick.
    private static final int SLACK = 2;

    private ResidentSwingGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ResidentSwingGameTests.class);
    }

    // A swing that never ended kept the next one from being sent, and kept model residents replaying theirs.
    @GameTest(template = TEMPLATE, timeoutTicks = 60)
    public static void aSwingEndsSoTheNextOneIsSwungAfresh(GameTestHelper helper) {
        ResidentEntity person = PersonBody.RESIDENT.get().create(helper.getLevel());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        person.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        helper.getLevel().addFreshEntity(person);
        int duration = person.getCurrentSwingDuration();
        person.swing(InteractionHand.MAIN_HAND);
        helper.assertTrue(person.swinging, "the swing starts");
        helper.runAfterDelay(duration + SLACK, () -> {
            helper.assertFalse(person.swinging, "the swing is over once its duration has run");
            helper.assertValueEqual(person.swingTime, 0, "and its clock is back at the start");
            person.swing(InteractionHand.MAIN_HAND);
            helper.assertTrue(person.swinging, "a later piece of work swings again");
            helper.runAfterDelay(SLACK, () -> {
                helper.assertTrue(person.swingTime > 0, "and that swing runs its clock too");
                person.discard();
                helper.succeed();
            });
        });
    }
}
