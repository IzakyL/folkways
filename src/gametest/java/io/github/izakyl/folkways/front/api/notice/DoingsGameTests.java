package io.github.izakyl.folkways.front.api.notice;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Doing;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DoingsGameTests {
    private static final ResourceLocation RIDING = ResourceLocation.fromNamespaceAndPath("folkways", "riding");
    private static final ResourceLocation STOWING = ResourceLocation.fromNamespaceAndPath("folkways", "stowing");
    private static final String PUMPKIN = "block.minecraft.pumpkin";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DoingsGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aRideToWorkReadsAsTheRideAndThenTheJob(GameTestHelper helper) {
        Doing riding = Doing.along(RIDING, 0.4F).headingFor(STOWING, Optional.of(PUMPKIN), Doing.Wares.NONE);
        TranslatableContents name = translated(Doings.name(riding));
        helper.assertTrue(name.getKey().equals("folkways.doing.onward"), "a ride to work reads as onward, got " + name.getKey());
        TranslatableContents now = translated((Component) name.getArgs()[0]);
        helper.assertTrue(now.getKey().equals("folkways.doing.folkways.riding"),
            "the ride reads as riding, and says nothing of how far along it is, got " + now.getKey());
        TranslatableContents then = translated((Component) name.getArgs()[1]);
        helper.assertTrue(then.getKey().equals("folkways.doing.folkways.stowing.about"), "then the job, with what it carries");
        helper.assertTrue(translated((Component) then.getArgs()[0]).getKey().equals(PUMPKIN), "the job names the goods");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aWalkToWorkStillReadsAsTheJob(GameTestHelper helper) {
        TranslatableContents name = translated(Doings.name(Doing.heading(STOWING, Optional.empty(), Doing.Wares.NONE)));
        helper.assertTrue(name.getKey().equals("folkways.doing.heading"), "a walk reads as heading, got " + name.getKey());
        helper.succeed();
    }

    private static TranslatableContents translated(Component component) {
        return (TranslatableContents) component.getContents();
    }
}
