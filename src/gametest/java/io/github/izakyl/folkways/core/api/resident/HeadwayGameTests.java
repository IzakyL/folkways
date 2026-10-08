package io.github.izakyl.folkways.core.api.resident;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class HeadwayGameTests {

    private static final String TEMPLATE = "empty";

    // Any plugin's verb reads the same; this one stands in for them.
    private static final ResourceLocation HARVESTING = ResourceLocation.fromNamespaceAndPath("folkways", "harvesting");

    private HeadwayGameTests() {
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(HeadwayGameTests.class);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void nothingIsKnownBeforeSettingOut(GameTestHelper helper) {
        helper.assertTrue(new Headway().fraction().isEmpty(),
            "a headway nobody has set out on should say nothing at all");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void groundCoveredIsTheFractionRead(GameTestHelper helper) {
        Headway headway = new Headway();
        headway.setOut(40.0D);

        headway.closer(10.0D);

        float read = headway.fraction().orElseThrow();
        helper.assertTrue(Math.abs(read - 0.75F) < 0.001F,
            "three quarters of the way there should read 0.75, got " + read);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void walkingRoundAWallLosesNoGround(GameTestHelper helper) {
        Headway headway = new Headway();
        headway.setOut(40.0D);
        headway.closer(10.0D);

        headway.closer(30.0D);

        float read = headway.fraction().orElseThrow();
        helper.assertTrue(Math.abs(read - 0.75F) < 0.001F,
            "a reading further out than the best so far should not count, got " + read);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aGuessedFractionRidesTheWire(GameTestHelper helper) {
        Doing hauling = Doing.along(Doings.STOWING, 0.5F);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        hauling.encode(buffer);
        Doing read = Doing.decode(buffer);

        helper.assertTrue(read.what().equals(Doings.STOWING),
            "the kind of doing should come back as it went, got " + read.what());
        helper.assertTrue(Math.abs(read.fraction(0L).orElseThrow() - 0.5F) < 0.001F,
            "a guessed fraction should come back as it went, got " + read.fraction(0L));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aStretchOfTicksRidesTheWireAndFillsAgainstTheClock(GameTestHelper helper) {
        Doing working = Doing.over(Doings.WORKING, 100L, 200L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        working.encode(buffer);
        Doing read = Doing.decode(buffer);

        float half = read.fraction(150L).orElseThrow();
        helper.assertTrue(Math.abs(half - 0.5F) < 0.001F,
            "halfway through the stretch should read 0.5, got " + half);
        helper.assertTrue(read.fraction(50L).orElseThrow() == 0.0F,
            "before it began should read nothing done, got " + read.fraction(50L));
        helper.assertTrue(read.fraction(500L).orElseThrow() == 1.0F,
            "past the end should read done, got " + read.fraction(500L));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void whatAJobIsDoneToRidesTheWire(GameTestHelper helper) {
        Doing.Wares carrots = new Doing.Wares(
            List.of(new Doing.Ware(ResourceLocation.withDefaultNamespace("carrot"), 0L)), List.of());
        Doing harvesting = Doing.over(HARVESTING, 100L, 200L).about(Optional.of("block.minecraft.carrots"))
            .with(carrots);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        harvesting.encode(buffer);
        Doing read = Doing.decode(buffer);

        helper.assertTrue(read.equals(harvesting), "the job and what it is done to should come back as they went, got " + read);

        Doing heading = Doing.heading(HARVESTING, Optional.of("block.minecraft.carrots"), carrots);
        heading.encode(buffer);
        Doing walked = Doing.decode(buffer);

        helper.assertTrue(walked.equals(heading), "a walk should still know the job it is heading for, got " + walked);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aDoingThatSaysNothingSaysNoFraction(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        Doing.open(Doings.WAITING).encode(buffer);

        Doing read = Doing.decode(buffer);
        helper.assertTrue(read.fraction(0L).isEmpty(),
            "waiting for a ride has no fraction to draw, got " + read.fraction(0L));
        helper.succeed();
    }
}
