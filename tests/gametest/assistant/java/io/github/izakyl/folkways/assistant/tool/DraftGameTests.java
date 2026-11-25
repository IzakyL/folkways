package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.DraftTools;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ActionBatch;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.vanilla.build.BuildContent;
import io.github.izakyl.folkways.vanilla.build.Commissions;
import io.github.izakyl.folkways.vanilla.build.draft.Drawn;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class DraftGameTests {

    private static final String PAD = """
            def draw(site):
                return [part("pad", box(site.zone), ["minecraft:stone_bricks"])]
            """;
    private static final String LIBRARY_PAD = """
            load("folkways_vanilla:materials.star", "offer")
            def draw(site):
                palette = offer(["minecraft:cobblestone"], ["minecraft:stone"])
                return [part("pad", box(site.zone), palette)]
            """;

    private DraftGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void inlineDraftDrawsAndLoadsInstalledLibraries(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var args = inline(fixture, PAD);
        String help = ScriptApi.help("pattern", false);
        helper.assertTrue(help.contains("part(role, region, palette") && help.contains("box(scope)")
                && help.contains("def draw(site):"), "pattern help describes vocabulary and examples");
        CompletableFuture<Void> check = host.run(fixture.player, inlineDraft(args), new JsonObject())
                .thenCompose(reply -> {
                    ok(helper, reply);
                    var drawing = reply.data().getAsJsonObject("result");
                    helper.assertTrue(drawing.get("ok").getAsBoolean() && drawing.get("blocks").getAsInt() == 1,
                            "inline script binding reports one block: " + reply.localized());
                    args.addProperty("source", LIBRARY_PAD);
                    return pureDraft(helper, fixture, args);
                }).thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().get("ok").getAsBoolean(), "installed library loads");
                    helper.assertTrue(reply.data().getAsJsonArray("materials").get(0).getAsJsonObject()
                            .get("item").getAsString().equals("minecraft:cobblestone"), "uses library palette");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void inlineDraftReportsLineNumbersAndRejectsOversize(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        var args = inline(fixture, "# comment\n" + "def draw(site)\n    return []\n");
        var tooLarge = inline(fixture, "x".repeat(Profile.MAX_PATTERN_SOURCE + 1));
        error(helper, pureDraft(helper, fixture, tooLarge).getNow(null), "pattern_source_too_large");
        var both = inline(fixture, PAD);
        both.addProperty("pattern", "folkways_vanilla:railway");
        error(helper, pureDraft(helper, fixture, both).getNow(null), "invalid_arguments");
        both.remove("source");
        both.remove("pattern");
        error(helper, pureDraft(helper, fixture, both).getNow(null), "invalid_arguments");
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenCompose(reply -> {
            checkDiagnostic(helper, reply, ":2:");
            args.addProperty("source", "def draw(site):\n    fail('try again')\n");
            return pureDraft(helper, fixture, args);
        }).thenAccept(reply -> checkDiagnostic(helper, reply, ":2:"));
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void inlineBuildSurvivesOrderSaveAndDrawsAgain(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        String source = LIBRARY_PAD.replace("    return",
                "    if site.matches(point(0, -1, 0), \"minecraft:gold_block\"):\n"
                + "        palette = [\"minecraft:stone_bricks\"]\n"
                + "    return");
        var args = inline(fixture, source);
        var at = args.getAsJsonObject("at");
        // Exercise a source beyond the NBT string and tool reply size limits.
        int padding = Profile.MAX_PATTERN_SOURCE - source.length() - 1;
        String script = "set(\"builds/pad\", {\"source\": " + args.get("source") + " + '#' + 'x' * "
                + padding + ", \"dimension\": " + args.get("dimension") + ", \"at\": " + at + "})";
        ScriptHost host = new ScriptHost();
        var before = fixture.colony.kept(BuildContent.ID).copy();
        CompletableFuture<Void> check = host.run(fixture.player, script, new JsonObject())
                .thenCompose(preview -> {
                    ok(helper, preview);
                    helper.assertFalse(preview.ghosts().isEmpty(), "the staged commission shows ghost blocks");
                    helper.assertTrue(before.equals(fixture.colony.kept(BuildContent.ID)), "confirmation precedes filing");
                    return host.decide(fixture.player, preview.data().get("revision").getAsString(), true);
                }).thenCompose(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("applied").get(0).getAsJsonObject()
                            .get("changed").getAsBoolean(), "confirmed inline build was filed");
                    var saved = roundTrip(fixture.colony.kept(BuildContent.ID));
                    var recipe = saved.getList("drafted", Tag.TAG_COMPOUND).getCompound(0);
                    helper.assertTrue(recipe.getByteArray("source").length == Profile.MAX_PATTERN_SOURCE,
                            "full inline program survives binary NBT");
                    helper.getLevel().setBlockAndUpdate(fixture.chest.above(11), Blocks.GOLD_BLOCK.defaultBlockState());
                    return Commissions.redraw(helper.getLevel(), recipe);
                }).thenAccept(drawing -> helper.assertTrue(drawing instanceof Drawn.Ready ready
                        && ready.draft().cells().size() == 1
                        && ready.draft().cells().getFirst().state().is(Blocks.STONE_BRICKS),
                        "reloaded recipe draws against the new terrain"));
        finish(helper, fixture, check);
    }

    private static JsonObject inline(ReadFixture fixture, String source) {
        JsonObject args = new JsonObject();
        args.addProperty("source", source);
        args.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        args.add("at", cell(fixture.chest.above(12)));
        return args;
    }

    private static String inlineDraft(JsonObject args) {
        return "result = draft(source=" + args.get("source") + ", dimension=" + args.get("dimension")
                + ", at=" + args.get("at") + ")";
    }

    private static void checkDiagnostic(GameTestHelper helper, ToolReply reply, String location) {
        ok(helper, reply);
        helper.assertTrue(!reply.data().get("ok").getAsBoolean()
                && reply.data().get("diagnostic").getAsString().contains(location),
                "refusal includes a source line: " + reply.localized());
    }

    private static CompoundTag roundTrip(CompoundTag tag) {
        try {
            var bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bytes);
            return NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), NbtAccounter.unlimitedHeap());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void railwayDraftIsPureAndSettingsReachTheDrawing(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonObject settings = new JsonObject();
        JsonArray ballast = new JsonArray();
        ballast.add("minecraft:gravel");
        settings.add("ballast", ballast);
        args.add("settings", settings);
        var holdings = List.copyOf(fixture.colony.claims());
        var kept = fixture.colony.kept(BuildContent.ID).copy();
        CompletableFuture<ToolReply> drawing = pureDraft(helper, fixture, args);
        CompletableFuture<Void> check = drawing.thenAccept(reply -> {
            ok(helper, reply);
            helper.assertTrue(reply.data().get("ok").getAsBoolean(), "railway draws: " + reply.localized());
            helper.assertTrue(reply.data().get("blocks").getAsInt() > 0, "nonempty draft");
            helper.assertTrue(reply.data().getAsJsonObject("bounds").has("from"), "absolute bounds");
            helper.assertTrue(reply.data().getAsJsonArray("materials").asList().stream().anyMatch(row ->
                    row.getAsJsonObject().get("item").getAsString().equals("minecraft:gravel")),
                    "ballast override is in the material bill");
            helper.assertTrue(holdings.equals(fixture.colony.claims()), "draft never registers holdings");
            helper.assertTrue(kept.equals(fixture.colony.kept(BuildContent.ID)), "draft never files a blueprint");
            helper.assertTrue(helper.getLevel().getBlockState(fixture.chest.above(12)).isAir(),
                    "draft never places a block");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void railwayConstraintsReturnLocalizedRefusalsBeforeConfirmation(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonObject end = args.getAsJsonArray("points").get(1).getAsJsonObject();
        end.addProperty("z", end.get("z").getAsInt() + 3);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertFalse(reply.data().get("ok").getAsBoolean(), "non-45-degree stretch refused");
            String text = Component.Serializer.toJson(reply.component("refusal"), fixture.player.registryAccess());
            helper.assertTrue(text.contains("error.rail.straight"), "pattern-owned refusal survives: " + text);
            helper.assertTrue(reply.data().get("bounds").isJsonNull(), "refused draft has no bounds");
        }).thenCompose(ignored -> host.run(fixture.player, commission(args), new JsonObject())).thenAccept(reply -> {
            error(helper, reply, "commission_refused");
            helper.assertFalse(reply.data().has("revision"), "rejected drawing never opens confirmation");
            helper.assertTrue(Component.Serializer.toJson(reply.component("refusal"), fixture.player.registryAccess())
                    .contains("error.rail.straight"), "commission carries the same refusal");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void pathCommissionFilesOnlyAfterConfirmationAndAppliesSettings(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonObject settings = new JsonObject();
        JsonArray ballast = new JsonArray();
        ballast.add("minecraft:gravel");
        settings.add("ballast", ballast);
        args.add("settings", settings);
        var before = fixture.colony.kept(BuildContent.ID).copy();
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player, commission(args), new JsonObject())
                .thenCompose(preview -> {
                    ok(helper, preview);
                    helper.assertTrue(before.equals(fixture.colony.kept(BuildContent.ID)), "preview never files");
                    String words = Component.Serializer.toJson(preview.component("summary"),
                            fixture.player.registryAccess());
                    helper.assertTrue(words.contains("folkways.assistant.preview.draft") && words.contains("gravel"),
                            "confirmation includes drawing bounds and materials");
                    return host.decide(fixture.player, preview.data().get("revision").getAsString(), true);
                }).thenCompose(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("applied").get(0).getAsJsonObject()
                            .get("changed").getAsBoolean(), "confirmed path was filed");
                    String saved = fixture.colony.kept(BuildContent.ID).toString();
                    helper.assertTrue(saved.contains("minecraft:gravel") && saved.contains("points"),
                            "native order stores the path and chosen ballast");
                    return host.run(fixture.player, """
                        id = ls("builds")[0]
                        result = get("builds/" + id)
                        delete("builds/" + id)
                        """, new JsonObject());
                }).thenCompose(preview -> {
                    ok(helper, preview);
                    var order = preview.data().getAsJsonObject("result");
                    helper.assertTrue(order.get("pattern").getAsString().equals("folkways_vanilla:railway")
                            && order.getAsJsonArray("points").size() == 2, "the filed order exposes its marks");
                    helper.assertFalse(ActionBatch.autoApprove(preview, java.util.Set.of("building")),
                            "cancellation requires manual approval");
                    return host.decide(fixture.player, preview.data().get("revision").getAsString(), true);
                }).thenAccept(cancelled -> {
                    ok(helper, cancelled);
                    helper.assertTrue(Commissioning.source().orElseThrow().orders(fixture.colony).stream()
                            .allMatch(Commissioning.Order::cancelling), "approval cancels the order");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void pathRevisionsCoverMidpointBlocksAndArguments(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonObject build = build(args);
        var registry = ActionTools.create();
        BlockPos middle = fixture.chest.above(13).east(6);
        var previous = helper.getLevel().getBlockState(middle);
        CompletableFuture<Void> check = registry.previewAsync(fixture.player, "commission_build", build)
                .thenCompose(preview -> {
                    ok(helper, preview);
                    helper.getLevel().setBlockAndUpdate(middle, Blocks.DIRT.defaultBlockState());
                    return registry.validateAsync(fixture.player, "commission_build", build,
                            preview.data().get("revision").getAsString()).handle((plan, failure) -> {
                                helper.getLevel().setBlockAndUpdate(middle, previous);
                                helper.assertTrue(failure != null && root(failure).getMessage().equals("stale"),
                                        "changed midpoint invalidates the signed drawing");
                                return null;
                            });
                }).thenCompose(ignored -> registry.previewAsync(fixture.player, "commission_build", build))
                .thenCompose(preview -> {
                    JsonObject changed = build.deepCopy();
                    var end = changed.getAsJsonArray("points").get(1).getAsJsonObject();
                    end.addProperty("x", end.get("x").getAsInt() + 1);
                    return registry.validateAsync(fixture.player, "commission_build", changed,
                            preview.data().get("revision").getAsString()).handle((plan, failure) -> {
                                helper.assertTrue(failure != null && root(failure).getMessage().equals("stale"),
                                        "path arguments are signed");
                                return null;
                            });
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptDraftLimitIsStructuredAndResetsForEachScript(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        String call = "draft(\"folkways_vanilla:railway\", \"" + args.get("dimension").getAsString()
                + "\", points=" + args.get("points") + ")";
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player,
                "result = [" + call + " for i in range(5)]", new JsonObject()).thenAccept(reply -> {
                    error(helper, reply, "draft_limit");
                    helper.assertFalse(reply.data().has("revision"), "limit leaves no intents");
                }).thenCompose(ignored -> host.run(fixture.player, "result = " + call, new JsonObject()))
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonObject("result").get("ok").getAsBoolean(),
                            "the next script has a fresh draft budget");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = 600)
    public static void draftRejectsMissingMembershipUnloadedOutsideAndOversizedMarks(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
        error(helper, pureDraft(helper, fixture, args).getNow(null), "not_member");
        Charters.keep(fixture.colony, Charter.founded("Assistant fixture", fixture.player.getUUID()));
        for (String code : List.of("unloaded", "outside_world", "invalid_path_size", "region_too_large",
                "conflicting_marks", "unknown_setting", "invalid_setting_value")) {
            JsonObject invalid = args.deepCopy();
            var points = invalid.getAsJsonArray("points");
            switch (code) {
                case "unloaded" -> points.asList().forEach(point -> point.getAsJsonObject().addProperty("x",
                        28_000_000 + point.getAsJsonObject().get("x").getAsInt() - fixture.chest.getX()));
                case "outside_world" -> points.get(0).getAsJsonObject().addProperty("y", -1000);
                case "invalid_path_size" -> points.remove(1);
                case "region_too_large" -> {
                    points.remove(1);
                    points.add(cell(fixture.chest.above(12).east(1024)));
                }
                case "conflicting_marks" -> invalid.add("at", cell(fixture.chest));
                case "unknown_setting", "invalid_setting_value" -> {
                    JsonObject settings = new JsonObject();
                    settings.addProperty(code.equals("unknown_setting") ? "missing" : "ballast", true);
                    invalid.add("settings", settings);
                }
                default -> throw new AssertionError(code);
            }
            error(helper, pureDraft(helper, fixture, invalid).getNow(null), code);
        }
        fixture.close();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void emptyItemKnobOverridesItsDefault(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonObject settings = new JsonObject();
        settings.add("ballast", new JsonArray());
        args.add("settings", settings);
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertFalse(reply.data().get("ok").getAsBoolean(), "empty palette does not inherit the default");
            helper.assertTrue(Component.Serializer.toJson(reply.component("refusal"), fixture.player.registryAccess())
                    .contains("error.material"), "the pattern explains the empty material slot");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void settingsClampCountsAndValidateChoicesAndFlags(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "folkways_assistant:assistant_knobs");
        args.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        args.add("at", cell(fixture.chest.above(12)));
        JsonObject settings = new JsonObject();
        settings.addProperty("length", 100);
        settings.addProperty("enabled", true);
        settings.addProperty("material", "pattern:dirt");
        args.add("settings", settings);
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertTrue(reply.data().get("ok").getAsBoolean(), "all knob types draw: " + reply.localized());
            helper.assertTrue(reply.data().get("blocks").getAsInt() == 4, "count clamps to schema maximum");
            helper.assertTrue(reply.data().getAsJsonArray("materials").get(0).getAsJsonObject()
                    .get("item").getAsString().equals("minecraft:dirt"), "choice reaches the drawing");
            JsonObject invalid = args.deepCopy();
            invalid.getAsJsonObject("settings").addProperty("material", "minecraft:missing");
            error(helper, pureDraft(helper, fixture, invalid).getNow(null), "invalid_setting_value");
            invalid.getAsJsonObject("settings").addProperty("material", "pattern:stone");
            invalid.getAsJsonObject("settings").addProperty("enabled", 1);
            error(helper, pureDraft(helper, fixture, invalid).getNow(null), "invalid_setting_value");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void curvedRailwayReportsJointsAndPathLengthIsBounded(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        JsonArray points = args.getAsJsonArray("points");
        points.set(1, cell(fixture.chest.above(12).east(24)));
        points.add(cell(fixture.chest.above(12).east(24).south(24)));
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertTrue(reply.data().get("ok").getAsBoolean(), "a 90-degree railway draws");
            helper.assertTrue(reply.data().get("joints").getAsInt() > 0, "curve reports rail joints");
            helper.assertTrue(reply.data().getAsJsonArray("materials").asList().stream()
                    .map(row -> row.getAsJsonObject()).filter(row -> row.get("item").getAsString()
                            .equals("create:track")).mapToLong(row -> row.get("count").getAsLong()).sum() > 30,
                    "the bill includes tracks between the curve endpoints");
            JsonObject tooLong = railway(fixture);
            JsonArray repeated = new JsonArray();
            for (int index = 0; index < 3000; index++) {
                repeated.add(cell(fixture.chest.above(12).east(index % 2 == 0 ? 0 : 12)));
            }
            tooLong.add("points", repeated);
            error(helper, pureDraft(helper, fixture, tooLong).getNow(null), "path_too_long");
            JsonArray tooMany = new JsonArray();
            for (int index = 0; index <= DraftTools.MAX_POINTS; index++) {
                tooMany.add(cell(fixture.chest.above(12)));
            }
            tooLong.add("points", tooMany);
            error(helper, pureDraft(helper, fixture, tooLong).getNow(null), "invalid_path_size");
            var source = Commissioning.source().orElseThrow();
            var request = new Commissioning.Request(ResourceLocation.parse("folkways_vanilla:railway"),
                    fixture.chest, fixture.chest, Direction.NORTH, "none",
                    Optional.of(Collections.nCopies(DraftTools.MAX_POINTS + 1, fixture.chest)),
                    new CompoundTag());
            helper.assertTrue(source.refusal(helper.getLevel(), fixture.colony, request).orElse("")
                    .equals("invalid_path_size"), "native source enforces Hint.MOST_POINTS too");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void drawingRechecksMembershipAndCancelledPreviewsNeverBecomePending(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        JsonObject args = railway(fixture);
        CompletableFuture<ToolReply> drawing = pureDraft(helper, fixture, args);
        Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
        CompletableFuture<Void> check = drawing.thenAccept(reply -> error(helper, reply, "not_member"))
                .thenCompose(ignored -> {
                    Charters.keep(fixture.colony, Charter.founded("Assistant fixture", fixture.player.getUUID()));
                    ActionBatch batch = new ActionBatch(ActionTools.create());
                    return batch.previewAsync(fixture.player,
                            List.of(new ActionBatch.Intent("commission_build", build(args))),
                            new ToolReply(new JsonObject(), List.of()), () -> true);
                }).thenAccept(reply -> {
                    error(helper, reply, "cancelled");
                    helper.assertFalse(reply.data().has("revision"), "cancelled preview has no confirmation handle");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void patternsExposeKnobsDefaultsOptionsAndLocalizedConstraints(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            Purity.assertPure(helper, "patterns", () -> {
                ToolReply reply = ReadTools.create().execute(fixture.player, "patterns", new JsonObject());
                ok(helper, reply);
                JsonObject railway = reply.data().getAsJsonArray("patterns").asList().stream()
                        .map(row -> row.getAsJsonObject()).filter(row -> row.get("id").getAsString()
                                .equals("folkways_vanilla:railway")).findFirst().orElseThrow();
                var pattern = Commissioning.source().orElseThrow().descriptions().stream()
                        .filter(row -> row.id().toString().equals("folkways_vanilla:railway")).findFirst().orElseThrow();
                var anchors = reply.texts().get(railway.getAsJsonObject("anchors").get("$text").getAsInt());
                helper.assertTrue(anchors.equals(pattern.marking().orElseThrow())
                        && anchors.getString().contains("path_point"), "anchors use the railway pattern's marking");
                helper.assertTrue(railway.getAsJsonArray("accepts").toString().equals("[\"path\"]"), "path only");
                helper.assertTrue(railway.getAsJsonArray("knobs").size() == 5, "all railway knobs exposed");
                var ballast = railway.getAsJsonArray("knobs").get(1).getAsJsonObject();
                helper.assertTrue(ballast.has("default") && ballast.has("options"), "knob defaults and options");
                helper.assertTrue(railway.getAsJsonObject("errors").get("error.rail.straight").isJsonObject(),
                        "constraints use opaque localized text");
                helper.assertTrue(ScriptApi.help("draft", false).contains("4 calls")
                        && ScriptApi.run(false).description().contains("patterns()"), "help and run share APIs");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void invalidSettingsKeepTheirCodeAndTranslatedRefusal(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        var source = Commissioning.source().orElseThrow();
        BlockPos at = fixture.chest.above(12);
        CompoundTag settings = new CompoundTag();
        settings.putBoolean("ballast", true);
        var request = new Commissioning.Request(ResourceLocation.parse("folkways_vanilla:railway"), at, at.east(12),
                Direction.NORTH, "none", Optional.of(List.of(at, at.east(12))), settings);
        Purity.assertPure(helper, "invalid native settings", () -> {
            var drawing = (Commissioning.Drawing.Refused) source.draw(helper.getLevel(), fixture.colony, request)
                    .getNow(null);
            helper.assertTrue(drawing.code().equals("invalid_setting_value"), "native draw retains the code");
            String key = "folkways.refusal.commission.invalid_setting_value";
            helper.assertTrue(Component.Serializer.toJson(drawing.refusal(), fixture.player.registryAccess())
                    .contains(key), "draw explains invalid settings rather than an unknown pattern");
            var filed = source.commission(fixture.player, helper.getLevel(), fixture.colony, request).getNow(null);
            helper.assertTrue(!filed.filed() && filed.code().orElse("").equals("invalid_setting_value"),
                    "native commission retains the same code");
            helper.assertTrue(Component.Serializer.toJson(filed.message(), fixture.player.registryAccess())
                    .contains(key), "native commission carries the same translated refusal");
        });
        JsonObject args = railway(fixture);
        JsonObject invalid = new JsonObject();
        invalid.addProperty("ballast", true);
        args.add("settings", invalid);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player,
                "result = draft(\"folkways_vanilla:railway\", \"" + args.get("dimension").getAsString()
                        + "\", points=" + args.get("points") + ", settings={\"ballast\": True})", new JsonObject())
                .thenAccept(reply -> {
                    error(helper, reply, "invalid_setting_value");
                    helper.assertTrue(reply.data().get("code").getAsString().equals("invalid_setting_value"),
                            "script errors keep the original code");
                    helper.assertTrue(Component.Serializer.toJson(reply.component("refusal"),
                            fixture.player.registryAccess()).contains("commission.invalid_setting_value"),
                            "script errors keep the translated refusal");
                }).thenCompose(ignored -> host.run(fixture.player, commission(args).replace("true", "True"),
                        new JsonObject())).thenAccept(reply -> {
                            error(helper, reply, "invalid_setting_value");
                            helper.assertTrue(reply.data().get("code").getAsString().equals("invalid_setting_value"),
                                    "confirmation preview keeps the original code");
                            helper.assertTrue(reply.data().has("refusal") && !reply.data().has("revision"),
                                    "invalid settings produce an explanation without a confirmation");
                        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_draft", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void unconnectableJointRefusalContainsItsAbsoluteEndpoints(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        BlockPos from = fixture.chest.above(12);
        BlockPos to = from.east();
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "folkways_assistant:assistant_bad_joint");
        args.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        args.add("at", cell(from));
        CompletableFuture<Void> check = pureDraft(helper, fixture, args).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertFalse(reply.data().get("ok").getAsBoolean(), "non-track endpoints are refused");
            helper.assertTrue(reply.data().get("code").getAsString().equals("joint_unconnectable"),
                    "connection failure differs from missing materials or an adapter");
            String text = Component.Serializer.toJson(reply.component("refusal"), fixture.player.registryAccess());
            helper.assertTrue(text.contains("folkways.refusal.draft.joint_unconnectable")
                    && text.contains(from.toShortString()) && text.contains(to.toShortString()),
                    "refusal identifies both absolute endpoints: " + text);
        }).thenCompose(ignored -> {
            JsonObject build = args.deepCopy();
            build.remove("dimension");
            build.add("at", fixture.at(from));
            return ActionTools.create().previewAsync(fixture.player, "commission_build", build);
        }).thenAccept(reply -> {
            error(helper, reply, "commission_refused");
            helper.assertTrue(reply.data().get("code").getAsString().equals("joint_unconnectable"),
                    "action preview keeps the native failure code");
            helper.assertFalse(reply.data().has("revision"), "invalid connection never opens confirmation");
        });
        finish(helper, fixture, check);
    }

    private static CompletableFuture<ToolReply> pureDraft(GameTestHelper helper, ReadFixture fixture, JsonObject args) {
        var result = new CompletableFuture<ToolReply>();
        Purity.assertPure(helper, "draft capture", () -> DraftTools.draft(fixture.player, args)
                .whenComplete((reply, failure) -> {
                    if (failure == null) {
                        result.complete(reply);
                    } else {
                        result.completeExceptionally(failure);
                    }
                }));
        return result;
    }

    private static JsonObject railway(ReadFixture fixture) {
        JsonObject args = new JsonObject();
        args.addProperty("pattern", "folkways_vanilla:railway");
        args.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        JsonArray points = new JsonArray();
        points.add(cell(fixture.chest.above(12)));
        points.add(cell(fixture.chest.above(12).east(12)));
        args.add("points", points);
        return args;
    }

    private static JsonObject build(JsonObject args) {
        JsonObject build = args.deepCopy();
        JsonObject at = new JsonObject();
        at.add("dimension", build.remove("dimension"));
        build.add("at", at);
        return build;
    }

    private static String commission(JsonObject args) {
        return "set(\"builds/railway\", " + args + ")";
    }

    private static JsonObject cell(BlockPos at) {
        JsonObject cell = new JsonObject();
        cell.addProperty("x", at.getX());
        cell.addProperty("y", at.getY());
        cell.addProperty("z", at.getZ());
        return cell;
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertTrue(reply != null && !reply.data().has("error"), "succeeded: " + reply);
    }

    private static void error(GameTestHelper helper, ToolReply reply, String code) {
        helper.assertTrue(reply != null && reply.data().has("error")
                && reply.data().get("error").getAsString().equals(code), "expected " + code + ": " + reply);
    }

    private static Throwable root(Throwable failure) {
        while (failure.getCause() != null) {
            failure = failure.getCause();
        }
        return failure;
    }

    private static void finish(GameTestHelper helper, ReadFixture fixture, CompletableFuture<?> check) {
        AsyncWait.succeedWhen(helper, check, () -> { }, fixture::close);
    }
}
