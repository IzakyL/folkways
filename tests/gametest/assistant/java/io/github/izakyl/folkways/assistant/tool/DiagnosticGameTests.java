package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.core.base.task.ColonyLog;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.runtime.RecentErrors;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.slf4j.LoggerFactory;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class DiagnosticGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_assistant:diagnostic_test");

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void reachableSeparatesKnownUnknownAndForbiddenStores(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ServerLevel level = helper.getLevel();
            var chest = (ChestBlockEntity) level.getBlockEntity(fixture.chest);
            JsonObject args = item();
            Purity.assertPure(helper, "unopened source", () -> {
                helper.assertTrue(reason(read(fixture, "reachable", args), "unknown_contents"),
                        "unopened loot is unknown");
            });
            helper.assertTrue(chest.getLootTable() != null, "reading did not open loot");
            chest.setLootTable(null);
            chest.setItem(0, new ItemStack(Items.BREAD, 7));
            BlockPos unloaded = fixture.chest.offset(20000, 0, 0);
            BlockPos unsupported = fixture.chest.offset(1, 0, 0);
            level.setBlockAndUpdate(unsupported, Blocks.CAMPFIRE.defaultBlockState());
            BlockPos foreign = fixture.chest.offset(64, 0, 0);
            level.setBlockAndUpdate(foreign, Blocks.CHEST.defaultBlockState());
            fixture.colony.hold(OWNER, new Claimed.Block(WorldPos.of(level, unloaded))).orElseThrow();
            fixture.colony.stores(OWNER, level.dimension(), List.of(WorldPos.of(level, unloaded),
                    WorldPos.of(level, unsupported), WorldPos.of(level, foreign)));
            fixture.warm();
            Purity.assertPure(helper, "reachable sources", () -> {
                JsonObject data = read(fixture, "reachable", args);
                helper.assertTrue(data.getAsJsonArray("sources").asList().stream().anyMatch(value ->
                        value.getAsJsonObject().get("kind").getAsString().equals("container")
                                && value.getAsJsonObject().get("count").getAsInt() == 7), "visible chest counted");
                helper.assertTrue(reason(data, "unloaded") && reason(data, "unsupported_storage")
                        && reason(data, "unauthorized"), "invisible bindings explain each refusal");
            });
            fixture.colony.rule(OWNER, level.dimension(), List.of(
                    new StoreRule.Access(WorldPos.of(level, fixture.chest), false, true)));
            Purity.assertPure(helper, "take rules", () -> helper.assertTrue(
                    reason(read(fixture, "reachable", args), "take_disabled"), "taking rules are respected"));
            JsonObject invalid = new JsonObject();
            invalid.addProperty("item", "folkways_assistant:missing");
            error(helper, fixture, "reachable", invalid, "unknown_item");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void reachableBoundsSourcesAndRespectsSlots(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ServerLevel level = helper.getLevel();
            var chest = (ChestBlockEntity) level.getBlockEntity(fixture.chest);
            chest.setLootTable(null);
            chest.setItem(0, new ItemStack(Items.BREAD, 3));
            chest.setItem(1, new ItemStack(Items.BREAD, 11));
            fixture.colony.rule(OWNER, level.dimension(), List.of(
                    new StoreRule.Slots(WorldPos.of(level, fixture.chest), 0, 1)));
            fixture.warm();
            Purity.assertPure(helper, "slot-limited source", () -> helper.assertTrue(
                    read(fixture, "reachable", item()).getAsJsonArray("sources").get(0).getAsJsonObject()
                            .get("count").getAsInt() == 3, "only admitted slots count as a source"));
            List<WorldPos> stores = new ArrayList<>();
            stores.add(WorldPos.of(level, fixture.chest.offset(1, 0, 0)));
            for (int index = 0; index < 257; index++) {
                stores.add(WorldPos.of(level, fixture.chest.offset(30000 + index * 16, 0, 0)));
            }
            fixture.colony.stores(OWNER, level.dimension(), stores);
            Purity.assertPure(helper, "bounded source reply", () -> {
                JsonObject data = read(fixture, "reachable", item());
                helper.assertTrue(data.getAsJsonArray("sources").size() + data.getAsJsonArray("invisible").size() == 128
                        && data.get("limited").getAsBoolean(), "sources are capped at 128 rows");
                helper.assertTrue(reason(data, "not_container"), "a missing container is a configuration issue");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void recentErrorsCaptureStacksAndEnforceLimitsAndMembership(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            String marker = UUID.randomUUID().toString();
            var logger = LoggerFactory.getLogger(DiagnosticGameTests.class);
            var loggers = List.of(logger, LoggerFactory.getLogger("folkways-labor"),
                    LoggerFactory.getLogger("folkways"));
            IllegalStateException broken = new IllegalStateException("diagnostic fixture");
            StackTraceElement[] stack = new StackTraceElement[12];
            for (int index = 0; index < stack.length; index++) {
                stack[index] = new StackTraceElement("DiagnosticFixture", "frame", "DiagnosticFixture.java", index);
            }
            broken.setStackTrace(stack);
            try (var ignored = ColonyLog.open(fixture.colony.id())) {
                for (var source : loggers) {
                    source.warn("diagnostic warning {}", marker);
                    source.error("diagnostic exception {}", marker, broken);
                    source.info("excluded info {}", marker);
                }
            }
            LoggerFactory.getLogger("other.mod").warn("excluded {}", marker);
            LoggerFactory.getLogger("folkways_other").warn("excluded {}", marker);
            Purity.assertPure(helper, "recent errors", () -> {
                var errors = read(fixture, "recent_errors", new JsonObject()).getAsJsonArray("errors");
                var rows = errors.asList().stream().map(value -> value.getAsJsonObject())
                        .filter(row -> row.get("message").getAsString().contains(marker)).toList();
                helper.assertTrue(rows.size() == 6, "only supported logger names and levels are captured");
                for (var source : loggers) {
                    var captured = rows.stream().filter(row -> row.get("logger").getAsString()
                            .equals(source.getName())).toList();
                    helper.assertTrue(captured.size() == 2
                            && captured.getFirst().get("level").getAsString().equals("WARN")
                            && captured.getLast().get("level").getAsString().equals("ERROR"),
                            "warnings and errors are captured for " + source.getName());
                    var error = captured.getLast();
                    helper.assertTrue(error.get("exception").getAsString().equals(IllegalStateException.class.getName())
                            && error.getAsJsonArray("frames").size() == 8 && error.has("time"), "stack is bounded");
                }
            });
            for (int index = 0; index < 205; index++) {
                logger.warn("ring " + marker + " " + index);
            }
            helper.assertTrue(RecentErrors.latest(200).size() == 200
                    && RecentErrors.latest(200).getFirst().message().endsWith(" 5"), "ring evicts oldest entries");
            JsonObject invalid = new JsonObject();
            invalid.addProperty("limit", 201);
            error(helper, fixture, "recent_errors", invalid, "bounds_limit");
            invalid.addProperty("limit", 0);
            error(helper, fixture, "recent_errors", invalid, "bounds_limit");
            fixture.player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.empty());
            for (String name : List.of("reachable", "recent_errors")) {
                error(helper, fixture, name, name.equals("reachable") ? item() : new JsonObject(), "not_member");
            }
            fixture.permission = 2;
            helper.assertFalse(ReadTools.create().execute(fixture.player, "recent_errors", new JsonObject())
                    .data().has("error"), "OP can read server logs without a colony");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void recentErrorsTrimRepliesToTheTransportBudget(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var logger = LoggerFactory.getLogger("folkways-budget-test");
            String marker = UUID.randomUUID().toString();
            try (var ignored = ColonyLog.open(fixture.colony.id())) {
                for (int index = 0; index < 32; index++) {
                    IllegalStateException error = largeError("entry" + index);
                    logger.error("{} {}", marker, index, error);
                }
            }
            JsonObject args = new JsonObject();
            args.addProperty("limit", 20);
            Purity.assertPure(helper, "budgeted logs", () -> {
                JsonObject data = read(fixture, "recent_errors", args);
                var rows = data.getAsJsonArray("errors");
                helper.assertTrue(data.get("truncated").getAsBoolean() && !rows.isEmpty() && rows.size() < 20,
                        "oldest rows are removed instead of losing the entire result");
                helper.assertTrue(rows.get(rows.size() - 1).getAsJsonObject().get("message").getAsString()
                        .equals(marker + " 31"), "the latest error survives budgeting");
                helper.assertTrue(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        < ToolReply.MAX_BYTES && data.has("dropped") && data.get("since").getAsLong() > 0,
                        "bounded replies retain collection metadata");
            });
            IllegalStateException oversized = largeError("oversized");
            for (int index = 0; index < 20; index++) {
                oversized.addSuppressed(largeError("suppressed" + index));
            }
            try (var ignored = ColonyLog.open(fixture.colony.id())) {
                logger.error("{} oversized", marker, oversized);
            }
            args.addProperty("limit", 1);
            JsonObject data = read(fixture, "recent_errors", args);
            var row = data.getAsJsonArray("errors").get(0).getAsJsonObject();
            helper.assertTrue(data.get("truncated").getAsBoolean() && row.get("details_truncated").getAsBoolean()
                    && row.getAsJsonArray("suppressed").isEmpty(),
                    "an individually oversized exception retains its root while trimming linked details");
        }
        helper.succeed();
    }

    private static IllegalStateException largeError(String method) {
        IllegalStateException error = new IllegalStateException("错误".repeat(300));
        StackTraceElement[] frames = new StackTraceElement[8];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement("DiagnosticBudget", method, "文".repeat(500), index);
        }
        error.setStackTrace(frames);
        return error;
    }

    private static JsonObject item() {
        JsonObject args = new JsonObject();
        args.addProperty("item", "minecraft:bread");
        return args;
    }

    private static JsonObject read(ReadFixture fixture, String name, JsonObject args) {
        ToolReply reply = ReadTools.create().execute(fixture.player, name, args);
        if (reply.data().has("error")) {
            throw new AssertionError(name + ": " + reply.data());
        }
        return reply.data();
    }

    private static boolean reason(JsonObject data, String reason) {
        return data.getAsJsonArray("invisible").asList().stream()
                .anyMatch(value -> value.getAsJsonObject().get("reason").getAsString().equals(reason));
    }

    private static void error(GameTestHelper helper, ReadFixture fixture, String name, JsonObject args, String code) {
        Purity.assertPure(helper, name + " refuses " + code, () -> helper.assertTrue(
                ReadTools.create().execute(fixture.player, name, args).data().get("error").getAsString().equals(code),
                code + " refused"));
    }

}
