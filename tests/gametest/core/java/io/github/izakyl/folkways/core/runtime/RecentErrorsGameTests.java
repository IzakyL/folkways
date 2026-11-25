package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.base.task.ColonyLog;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RecentErrorsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RecentErrorsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void fingerprintsKeepFirstAndLastTimesAndNewestDetails(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        try (var ignored = ColonyLog.open(UUID.randomUUID())) {
            append(colony, 1000, new ParameterizedMessage("request {}", "first"), null);
            append(colony, 2000, new ParameterizedMessage("request {}", "last"), null);
            var warning = RecentErrors.snapshot(200, Optional.of(colony)).entries().getFirst();
            helper.assertTrue(warning.count() == 2 && warning.firstTime() == 1000 && warning.lastTime() == 2000
                    && warning.time() == 2000 && warning.message().equals("request last"),
                    "a message template merges parameters but retains the newest content");
            IllegalStateException first = error("first");
            IllegalStateException last = error("last");
            append(colony, 3000, new SimpleMessage("first wrapper"), first);
            append(colony, 4000, new SimpleMessage("last wrapper"), last);
            var failure = RecentErrors.snapshot(200, Optional.of(colony)).entries().getLast();
            helper.assertTrue(failure.count() == 2 && failure.firstTime() == 3000 && failure.lastTime() == 4000
                    && failure.exceptionMessage().equals(Optional.of("last"))
                    && failure.message().equals("last wrapper"), "exception fingerprints ignore changing messages");
            helper.assertTrue(failure.colony().equals(Optional.of(colony)),
                    "the event context supplies the origin, independently of the appending thread");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void exceptionTreesBoundDepthWidthFramesMessagesAndCycles(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        IllegalStateException root = error("root".repeat(200));
        Throwable tail = root;
        for (int index = 0; index < 8; index++) {
            IllegalStateException child = error("cause".repeat(200));
            tail.initCause(child);
            tail = child;
        }
        for (int index = 0; index < 20; index++) {
            root.addSuppressed(error("suppressed".repeat(100)));
        }
        append(colony, 1000, new SimpleMessage("message".repeat(200)), root);
        var row = RecentErrors.snapshot(1, Optional.of(colony)).entries().getFirst();
        helper.assertTrue(row.message().length() == 500 && row.exceptionMessage().orElseThrow().length() == 500
                && row.detailsTruncated(), "all messages are capped and omitted details are explicit");
        int depth = 0;
        Optional<RecentErrors.Failure> cause = row.cause();
        while (cause.isPresent()) {
            var failure = cause.get();
            helper.assertTrue(failure.frames().size() == 4 && failure.message().length() == 500,
                    "each cause retains at most four frames and 500 message characters");
            depth++;
            cause = failure.cause();
        }
        helper.assertTrue(depth == 4 && row.suppressed().size() == 12,
                "four cause levels and at most sixteen total linked exceptions are retained");
        helper.assertTrue(row.suppressed().stream().allMatch(failure -> failure.frames().size() == 4
                && failure.message().length() == 500), "suppressed exceptions obey the same bounds");
        IllegalStateException cycle = error("cycle");
        IllegalStateException child = error("child");
        cycle.initCause(child);
        child.addSuppressed(cycle);
        append(colony, 2000, new SimpleMessage("cycle"), cycle);
        row = RecentErrors.snapshot(1, Optional.of(colony)).entries().getFirst();
        helper.assertTrue(row.cause().orElseThrow().suppressed().isEmpty() && row.detailsTruncated(),
                "cycles stop at already visited exceptions");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void evictionCountsFingerprintsAndRefreshesRepeatedEntries(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        for (int index = 0; index < 200; index++) {
            append(colony, index, new SimpleMessage("distinct " + index), null);
        }
        append(colony, 1000, new SimpleMessage("distinct 0"), null);
        var before = RecentErrors.snapshot(200, Optional.of(colony));
        append(colony, 1001, new SimpleMessage("distinct 200"), null);
        var after = RecentErrors.snapshot(200, Optional.of(colony));
        helper.assertTrue(after.entries().size() == 200 && after.dropped() == before.dropped() + 1
                && after.since() == before.since() && after.since() > 0,
                "eviction increments dropped, preserving since");
        helper.assertTrue(after.entries().stream().anyMatch(row ->
                row.message().equals("distinct 0") && row.count() == 2)
                && after.entries().stream().noneMatch(row -> row.message().equals("distinct 1")),
                "a repeated fingerprint moves to the newest position without consuming another slot");
        helper.succeed();
    }

    private static IllegalStateException error(String message) {
        IllegalStateException error = new IllegalStateException(message);
        StackTraceElement[] stack = new StackTraceElement[8];
        for (int index = 0; index < stack.length; index++) {
            stack[index] = new StackTraceElement("DiagnosticFixture", "frame", "DiagnosticFixture.java", index);
        }
        error.setStackTrace(stack);
        return error;
    }

    private static void append(UUID colony, long time, Message message, Throwable error) {
        var context = new SortedArrayStringMap();
        context.putValue(ColonyLog.KEY, colony.toString());
        var event = Log4jLogEvent.newBuilder().setLoggerName("folkways-diagnostic-test")
                .setLevel(error == null ? Level.WARN : Level.ERROR).setMessage(message).setThrown(error)
                .setContextData(context).setTimeMillis(time).build();
        var appender = ((LoggerContext) LogManager.getContext(false)).getRootLogger().getAppenders()
                .get("folkways-recent-errors");
        ((RecentErrors) appender).append(event);
    }
}
