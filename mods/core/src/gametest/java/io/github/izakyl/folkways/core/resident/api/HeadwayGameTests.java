package io.github.izakyl.folkways.core.resident.api;

import io.github.izakyl.folkways.FolkwaysMod;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
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

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
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

    @GameTest(template = TEMPLATE)
    public static void unknownProgressNumbersFailDecoding(GameTestHelper helper) {
        for (int type : new int[]{-1, 2, 127}) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeResourceLocation(HARVESTING);
                buffer.writeBoolean(false);
                buffer.writeBoolean(false);
                buffer.writeBoolean(true);
                buffer.writeVarInt(type);
                buffer.writeVarLong(100);
                buffer.writeVarLong(200);
                buffer.writeVarInt(0);
                buffer.writeVarInt(0);
                boolean rejected = false;
                try {
                    Doing.decode(buffer);
                } catch (DecoderException expected) {
                    rejected = true;
                }
                helper.assertTrue(rejected, "unknown progress numbers must fail: " + type);
            } finally {
                buffer.release();
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void nothingIsKnownBeforeSettingOut(GameTestHelper helper) {
        helper.assertTrue(new Headway().fraction().isEmpty(),
            "a headway nobody has set out on should say nothing at all");
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
    public static void aDoingRidesTheWireAsItWent(GameTestHelper helper) {
        Doing.Wares carrots = new Doing.Wares(
            List.of(new Doing.Ware(ResourceLocation.withDefaultNamespace("carrot"), 0L)), List.of());
        // A guessed fraction, a stretch of ticks, what a job is done to, a walk heading for one, and a wait.
        List<Doing> sent = List.of(Doing.along(Doings.STOWING, 0.5F), Doing.over(Doings.WORKING, 100L, 200L),
            Doing.over(HARVESTING, 100L, 200L).about(Optional.of("block.minecraft.carrots")).with(carrots),
            Doing.heading(HARVESTING, Optional.of("block.minecraft.carrots"), carrots), Doing.open(Doings.WAITING));
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        for (Doing doing : sent) {
            doing.encode(buffer);
            Doing read = Doing.decode(buffer);
            helper.assertTrue(read.equals(doing), "a doing should come back as it went, got " + read);
        }

        helper.assertTrue(Math.abs(sent.get(0).fraction(0L).orElseThrow() - 0.5F) < 0.001F,
            "a guessed fraction reads as guessed, got " + sent.get(0).fraction(0L));
        Doing working = sent.get(1);
        helper.assertTrue(Math.abs(working.fraction(150L).orElseThrow() - 0.5F) < 0.001F,
            "halfway through the stretch should read 0.5, got " + working.fraction(150L));
        helper.assertTrue(working.fraction(50L).orElseThrow() == 0.0F,
            "before it began should read nothing done, got " + working.fraction(50L));
        helper.assertTrue(working.fraction(500L).orElseThrow() == 1.0F,
            "past the end should read done, got " + working.fraction(500L));
        helper.assertTrue(sent.get(4).fraction(0L).isEmpty(),
            "waiting for a ride has no fraction to draw, got " + sent.get(4).fraction(0L));
        helper.succeed();
    }
}
