package io.github.izakyl.folkways.front.engine.colony;

import io.github.izakyl.folkways.FolkwaysMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class IncidentFeedGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(IncidentFeedGameTests.class); }

    @GameTest(template = "empty")
    public static void firstIncidentIsImmediateAndNineteenInterruptionsKeepTheirCount(GameTestHelper helper) {
        IncidentFeed feed = new IncidentFeed();
        List<Long> counts = new ArrayList<>();
        for (int n = 0; n < 19; n++) feed.offer("pickup/flee", Component.literal("interrupted"), 0,
            (message, count) -> counts.add(count));
        helper.assertTrue(counts.equals(List.of(1L)), "the first event is sent without waiting for a heartbeat");
        feed.flush(99, (message, count) -> counts.add(count));
        helper.assertValueEqual(counts.size(), 1, "repeats cannot flood chat during their grouping interval");
        feed.flush(100, (message, count) -> counts.add(count));
        helper.assertTrue(counts.equals(List.of(1L, 18L)), "the other eighteen interruptions must not be lost");
        feed.flush(200, (message, count) -> counts.add(count));
        helper.assertValueEqual(counts.size(), 2, "a drained group is not sent twice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void differentFailuresShareABurstLimitAndEventuallyDeliver(GameTestHelper helper) {
        IncidentFeed feed = new IncidentFeed();
        List<Long> counts = new ArrayList<>();
        for (int n = 0; n < 12; n++) feed.offer("path/" + n, Component.literal("unreachable"), 0,
            (message, count) -> counts.add(count));
        helper.assertValueEqual(counts.size(), 4, "different node IDs cannot bypass the per-reader burst limit");
        feed.flush(39, (message, count) -> counts.add(count));
        helper.assertValueEqual(counts.size(), 4, "pending new errors also respect the window");
        feed.flush(40, (message, count) -> counts.add(count));
        feed.flush(80, (message, count) -> counts.add(count));
        helper.assertValueEqual(counts.size(), 12, "queued errors are eventually delivered in bounded groups");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void largeBurstsRemainBoundedAndReportTheirOverflow(GameTestHelper helper) {
        IncidentFeed feed = new IncidentFeed();
        List<Component> messages = new ArrayList<>();
        for (int n = 0; n < 10_000; n++) feed.offer("error/" + n, Component.literal("error"), 0,
            (message, count) -> messages.add(message));
        for (int tick = 40; tick <= 680; tick += 40) feed.flush(tick, (message, count) -> messages.add(message));
        helper.assertValueEqual(messages.size(), 65, "bounded detail entries plus a single overflow summary");
        var contents = (net.minecraft.network.chat.contents.TranslatableContents) messages.getLast().getContents();
        helper.assertTrue(contents.getKey().equals("folkways.debug.incident.overflow")
                && ((Number) contents.getArgs()[0]).longValue() == 9_936,
            "the bounded queue must account for the events it could not retain individually");
        helper.succeed();
    }
}
