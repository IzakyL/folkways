package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolContext;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.ReadScope;
import io.github.izakyl.folkways.assistant.tool.internal.ActionBatch;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.assistant.tool.internal.SurveyReads;
import io.github.izakyl.folkways.assistant.tool.internal.Surveys;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.colony.internal.PointSites;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.lang.reflect.Proxy;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class SurveyGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.parse("folkways_assistant:survey_test");

    static {
        PointSites.register(OWNER, (level, pos) -> true);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void holdingsUseActualCellsAndInterpolatedLines(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var level = helper.getLevel();
            BlockPos origin = fixture.chest.offset(128, 0, 0);
            Set<WorldPos> cells = new LinkedHashSet<>();
            for (int x = 0; x < 40; x++) {
                for (int z = 0; z < 40; z++) {
                    cells.add(WorldPos.of(level, origin.offset(x, 0, z)));
                }
            }
            fixture.colony.hold(OWNER, new Claimed.Area(cells)).orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, origin.offset(80, 0, 0)),
                    WorldPos.of(level, origin.offset(120, 20, 40)),
                    WorldPos.of(level, origin.offset(120, 20, 80))))).orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Point(WorldPos.of(level, fixture.chest.offset(0, 3, 4))))
                    .orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Area(Set.of(WorldPos.of(level, origin.offset(160, 0, 0)),
                    WorldPos.of(level, origin.offset(200, 0, 40))))).orElseThrow();
            Purity.assertPure(helper, "indexed holding scope", () -> {
                ReadScope scope = new ReadScope(context(fixture));
                helper.assertTrue(scope.contains(level, fixture.chest.offset(-8, 8, -8)), "block padding included");
                helper.assertFalse(scope.contains(level, fixture.chest.offset(-9, 0, 0)), "block padding bounded");
                helper.assertTrue(scope.contains(level, origin.offset(20, 0, 20)), "area interior included");
                helper.assertTrue(scope.contains(level, origin.offset(47, 8, 47)), "area padding included");
                helper.assertFalse(scope.contains(level, origin.offset(48, 0, 20)), "area padding bounded");
                helper.assertTrue(scope.contains(level, origin.offset(100, 10, 20)), "diagonal middle included");
                helper.assertTrue(scope.contains(level, origin.offset(120, 20, 60)), "second segment included");
                helper.assertFalse(scope.contains(level, origin.offset(80, 20, 40)), "line box is not permission");
                helper.assertTrue(scope.contains(level, fixture.chest.offset(8, 11, 12)), "point padding included");
                helper.assertFalse(scope.contains(level, origin.offset(180, 0, 20)), "area hole stays outside");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void surveysRequireConfirmationAndKeepOnlyFour(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ActionRegistry actions = ActionTools.create();
            ActionBatch batches = new ActionBatch(actions);
            BlockPos start = fixture.chest.offset(160, 0, 0);
            JsonObject args = survey(fixture, start, start.east(100));
            ToolReply output = new ToolReply(new JsonObject(), List.of());
            ToolReply preview = batches.preview(fixture.player,
                    List.of(new ActionBatch.Intent("request_survey", args)), output);
            helper.assertFalse(ActionBatch.autoApprove(preview, Set.of("surveys", "drawings")), "always manual");
            helper.assertTrue(Surveys.of(context(fixture)).isEmpty(), "preview grants nothing");
            ToolReply declined = batches.decide(fixture.player, preview.data().get("revision").getAsString(), false)
                    .join();
            helper.assertTrue(declined.data().get("declined").getAsBoolean(), "decline reported");
            helper.assertTrue(Surveys.of(context(fixture)).isEmpty(), "decline grants nothing");
            preview = batches.preview(fixture.player, List.of(new ActionBatch.Intent("request_survey", args)), output);
            String revision = preview.data().get("revision").getAsString();
            ok(helper, batches.decide(fixture.player, revision, true).join());
            error(helper, batches.decide(fixture.player, revision, true).join(), "stale");
            ReadScope scope = new ReadScope(context(fixture));
            helper.assertTrue(scope.contains(helper.getLevel(), start.offset(50, 32, 16)), "corridor padding");
            helper.assertFalse(scope.contains(helper.getLevel(), start.offset(50, 33, 0)), "vertical bound");
            helper.assertFalse(scope.contains(helper.getLevel(), start.offset(50, 0, 17)), "horizontal bound");
            for (int index = 1; index <= 4; index++) {
                JsonObject next = survey(fixture, start.south(index * 100), start.south(index * 100).east(10));
                ToolReply signed = actions.preview(fixture.player, "request_survey", next);
                ok(helper, actions.apply(fixture.player, "request_survey", next,
                        signed.data().get("revision").getAsString()).join());
            }
            helper.assertTrue(Surveys.of(context(fixture)).size() == 4, "four corridors maximum");
            helper.assertFalse(new ReadScope(context(fixture)).contains(helper.getLevel(), start), "oldest evicted");
            fixture.warm();
            Purity.assertPure(helper, "surveys list", () -> {
                ToolReply reply = ToolRegistry.READS.execute(fixture.player, "surveys", new JsonObject());
                helper.assertTrue(reply.data().getAsJsonArray("surveys").size() == 4, "list confirmed corridors");
            });
            ScriptHost.logout(new PlayerEvent.PlayerLoggedOutEvent(
                    fixture.player));
            helper.assertTrue(Surveys.of(context(fixture)).isEmpty(), "disconnect clears corridors");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void surveyValidationAndMembershipAreCheckedAgain(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ActionRegistry actions = ActionTools.create();
            JsonObject tooFew = survey(fixture, fixture.chest, fixture.chest);
            tooFew.getAsJsonArray("points").remove(1);
            error(helper, actions.preview(fixture.player, "request_survey", tooFew), "invalid_survey_size");
            JsonObject outside = survey(fixture, fixture.chest,
                    fixture.chest.atY(helper.getLevel().getMaxBuildHeight()));
            error(helper, actions.preview(fixture.player, "request_survey", outside), "outside_world");
            JsonObject longPath = survey(fixture, fixture.chest, fixture.chest.east(1025));
            error(helper, actions.preview(fixture.player, "request_survey", longPath), "survey_too_long");
            JsonObject args = survey(fixture, fixture.chest, fixture.chest.east(1024));
            ToolReply preview = actions.preview(fixture.player, "request_survey", args);
            ok(helper, preview);
            JsonObject changed = survey(fixture, fixture.chest, fixture.chest.east(1023));
            error(helper, actions.apply(fixture.player, "request_survey", changed,
                    preview.data().get("revision").getAsString()).join(), "stale");
            JsonObject roundTrip = survey(fixture, fixture.chest, fixture.chest.east(600));
            roundTrip.getAsJsonArray("points").add(xyz(fixture, fixture.chest));
            error(helper, actions.preview(fixture.player, "request_survey", roundTrip), "survey_too_long");
            Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
            error(helper, actions.apply(fixture.player, "request_survey", args,
                    preview.data().get("revision").getAsString()).join(), "not_member");
            for (String name : List.of("terrain", "colony_block", "surveys")) {
                error(helper, ToolRegistry.READS.execute(fixture.player, name, new JsonObject()), "not_member");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void terrainAndBlocksArePureAndDoNotExposeContainers(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var level = helper.getLevel();
            BlockPos ground = fixture.chest.south(4);
            level.setBlockAndUpdate(ground, Blocks.STONE.defaultBlockState());
            for (int y = 1; y <= 8; y++) {
                level.setBlockAndUpdate(ground.above(y), Blocks.AIR.defaultBlockState());
            }
            level.setBlockAndUpdate(ground.above(2), Blocks.OAK_LEAVES.defaultBlockState());
            fixture.warm();
            Purity.assertPure(helper, "terrain and restricted block", () -> {
                ToolReply reply = readBlock(fixture, fixture.chest);
                ok(helper, reply);
                helper.assertTrue(reply.data().get("id").getAsString().equals("minecraft:chest"), "block id");
                helper.assertFalse(reply.data().has("nbt") || reply.data().has("items"), "no container data");
                helper.assertTrue(reply.data().get("collision").getAsString().equals("partial"), "partial shape");
                JsonObject row = column(helper, fixture, ground, ground.above(3));
                helper.assertTrue(row.get("ground_y").getAsInt() == ground.getY(), "leaves excluded from ground");
                helper.assertTrue(row.get("surface").getAsString().equals("plant"), "plant surface");
                helper.assertTrue(row.get("clearance").getAsInt() == 1, "clearance stops at leaves");
                error(helper, readBlock(fixture, fixture.chest.east(64)), "outside_survey");
                helper.assertTrue(column(helper, fixture, ground.east(64), ground.east(64).above(3))
                        .get("surface").getAsString().equals("unknown"), "outside column unknown");
            });
            level.setBlockAndUpdate(ground.above(2), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(ground.above(), Blocks.WATER.defaultBlockState());
            JsonObject wet = column(helper, fixture, ground, ground.above(3));
            helper.assertTrue(wet.get("ground_y").getAsInt() == ground.getY(), "ground passes through water");
            helper.assertTrue(wet.get("surface").getAsString().equals("fluid"), "fluid surface");
            helper.assertTrue(wet.get("clearance").getAsInt() == 0, "water blocks clearance");
            level.setBlockAndUpdate(ground.above(), Blocks.AIR.defaultBlockState());
            JsonObject dry = column(helper, fixture, ground.above(3), ground.above(3));
            helper.assertTrue(dry.get("ground_y").getAsInt() == ground.getY(), "corner Y does not clip ground");
            helper.assertTrue(dry.get("surface").getAsString().equals("solid"), "natural surface");
            helper.assertTrue(dry.get("clearance").getAsInt() == 8, "clearance capped at eight");
            helper.assertTrue(readBlock(fixture, ground).data().get("collision").getAsString().equals("full"),
                    "full collision");
            helper.assertTrue(readBlock(fixture, ground.above()).data().get("collision").getAsString().equals("none"),
                    "no collision");
            level.setBlockAndUpdate(ground, Blocks.BRICKS.defaultBlockState());
            helper.assertTrue(column(helper, fixture, ground, ground).get("surface").getAsString().equals("built"),
                    "built surface");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedColumnsAndSamplingLimits(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            BlockPos far = new BlockPos(20_000_000, 64, 20_000_000);
            fixture.colony.hold(OWNER, new Claimed.Area(Set.of(WorldPos.of(helper.getLevel(), far)))).orElseThrow();
            Purity.assertPure(helper, "unloaded survey reads", () -> {
                helper.assertTrue(column(helper, fixture, far, far.above(2)).get("ground_y").isJsonNull(),
                        "unloaded column unknown");
                error(helper, readBlock(fixture, far), "unloaded");
                helper.assertTrue(helper.getLevel().getChunkSource().getChunkNow(far.getX() >> 4, far.getZ() >> 4)
                        == null, "did not load chunk");
                JsonObject args = box(fixture, far, far.offset(64, 0, 64));
                error(helper, ToolRegistry.READS.execute(fixture.player, "terrain", args), "column_limit");
                args.addProperty("step", 2);
                ToolReply sampled = SurveyReads.terrain(context(fixture), args);
                helper.assertTrue(sampled.data().getAsJsonArray("columns").size() == 1089, "step applied first");
                args = box(fixture, far, far.offset(63, 0, 63));
                ToolReply max = SurveyReads.terrain(context(fixture), args);
                helper.assertTrue(max.data().getAsJsonArray("columns").size() == 4096, "4096 columns allowed");
                helper.assertFalse(max.data().get("limited").getAsBoolean(), "complete sampling");
                args.addProperty("step", 0);
                error(helper, ToolRegistry.READS.execute(fixture.player, "terrain", args), "bounds_limit");
                args = box(fixture, far, far);
                args.getAsJsonObject("to").addProperty("dimension", "minecraft:the_nether");
                error(helper, ToolRegistry.READS.execute(fixture.player, "terrain", args), "mixed_dimensions");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void columnIntervalsMatchPointAuthorization(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var level = helper.getLevel();
            BlockPos origin = fixture.chest.offset(256, 0, 256).atY(64);
            fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, origin),
                    WorldPos.of(level, origin.offset(18, 9, 12)), WorldPos.of(level, origin.offset(2, -4, 24)))))
                    .orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, origin.offset(8, 40, 8)),
                    WorldPos.of(level, origin.offset(8, 26, 8))))).orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Line(List.of(WorldPos.of(level, origin.offset(30, -10, 30)),
                    WorldPos.of(level, origin.offset(31, 40, 31))))).orElseThrow();
            fixture.colony.hold(OWNER, new Claimed.Area(Set.of(WorldPos.of(level, origin.offset(-2, 0, -2)),
                    WorldPos.of(level, origin.offset(-1, 0, -2)), WorldPos.of(level, origin.offset(20, 0, -2)))))
                    .orElseThrow();
            ReadScope scope = new ReadScope(context(fixture));
            Purity.assertPure(helper, "column intervals", () -> {
                for (int x = -9; x <= 27; x += 3) {
                    for (int z = -9; z <= 33; z += 3) {
                        int columnX = origin.getX() + x;
                        int columnZ = origin.getZ() + z;
                        var intervals = scope.column(Realm.of(level), columnX, columnZ);
                        helper.assertTrue(intervals.equals(scope.column(level, columnX, columnZ)),
                                "dimension column matches storage column");
                        for (int y = 40; y <= 120; y++) {
                            boolean allowed = false;
                            for (var interval : intervals) {
                                allowed |= interval.contains(y);
                            }
                            BlockPos pos = new BlockPos(columnX, y, columnZ);
                            helper.assertTrue(allowed == scope.contains(level, pos), "column and point agree: " + pos);
                        }
                    }
                }
                helper.assertTrue(scope.column(Realm.of(level), origin.getX() + 9, origin.getZ() - 10).isEmpty(),
                        "row compression does not authorize holes between runs");
                var separated = scope.column(Realm.of(level), origin.getX() + 8, origin.getZ() + 8);
                helper.assertTrue(separated.size() == 2, "separated authorization bands remain separate");
                helper.assertTrue(separated.get(0).min() > separated.get(1).max(), "intervals highest first");
            });
            BlockPos gap = origin.offset(8, 24, 8);
            level.setBlockAndUpdate(gap.below(24), Blocks.STONE.defaultBlockState());
            for (int y = 82; y <= 112; y++) {
                level.setBlockAndUpdate(gap.atY(y), Blocks.AIR.defaultBlockState());
            }
            helper.assertTrue(column(helper, fixture, gap, gap).get("surface").getAsString().equals("unknown"),
                    "an empty upper band does not search below an unauthorized gap");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void fullTerrainQueryBoundsScopeWorkAndBuildsOneIndex(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var level = helper.getLevel();
            helper.assertTrue(level.getServer().isSameThread(), "terrain measured on server thread");
            BlockPos ground = fixture.chest.offset(1024, 0, 1024).atY(64);
            for (BlockPos pos : BlockPos.betweenClosed(ground, ground.offset(127, 0, 31))) {
                level.setBlockAndUpdate(pos, Blocks.STONE.defaultBlockState());
            }
            JsonObject args = survey(fixture, ground.south(15), ground.south(15).east(127));
            ActionRegistry actions = ActionTools.create();
            ToolReply preview = actions.preview(fixture.player, "request_survey", args);
            ok(helper, actions.apply(fixture.player, "request_survey", args,
                    preview.data().get("revision").getAsString()).join());
            Set<WorldPos> cells = new LinkedHashSet<>();
            BlockPos area = ground.east(512);
            for (BlockPos pos : BlockPos.betweenClosed(area, area.offset(199, 0, 99))) {
                cells.add(WorldPos.of(level, pos));
            }
            fixture.colony.hold(OWNER, new Claimed.Area(cells)).orElseThrow();
            long started = System.nanoTime();
            ReadScope scope = new ReadScope(context(fixture));
            long buildNanos = System.nanoTime() - started;
            helper.assertTrue(scope.column(Realm.of(level), area.getX() + 100, area.getZ() + 50)
                    .equals(List.of(new ReadScope.Interval(56, 72))), "large area retains its authorization");
            int[] resolutions = {0};
            int[] builds = {0};
            Colony observed = (Colony) Proxy.newProxyInstance(Colony.class.getClassLoader(),
                    new Class<?>[]{Colony.class},
                    (proxy, method, values) -> {
                        if (method.getName().equals("claims")) {
                            builds[0]++;
                        }
                        return method.invoke(fixture.colony, values);
                    });
            ToolContext context = new ToolContext(fixture.player, observed);
            Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
                @Override
                public Optional<WorldSpaces.Frame> frame(Level ignored, UUID id) {
                    return Optional.empty();
                }

                @Override
                public Optional<WorldSpaces.Frame> containing(Level ignored, BlockPos pos) {
                    resolutions[0]++;
                    return Optional.empty();
                }

                @Override
                public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                    return Optional.empty();
                }
            });
            try {
                Purity.assertPure(helper, "4096 loaded survey columns", () -> {
                    long reading = System.nanoTime();
                    ToolReply reply = SurveyReads.terrain(context, box(fixture, ground, ground.offset(127, 0, 31)));
                    long readNanos = System.nanoTime() - reading;
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("columns").size() == 4096, "full query completed");
                    helper.assertTrue(builds[0] == 1, "one scope index per terrain call: " + builds[0]);
                    helper.assertTrue(resolutions[0] <= 4096 * (65 + 8),
                            "scope work bounded by corridor height and clearance: " + resolutions[0]);
                    for (var value : reply.data().getAsJsonArray("columns")) {
                        JsonObject row = value.getAsJsonObject();
                        helper.assertTrue(row.get("ground_y").getAsInt() == 64, "loaded authorized ground");
                        helper.assertTrue(row.get("clearance").getAsInt() == 8, "clearance preserved");
                    }
                    LogUtils.getLogger().info("Survey performance: 20000 area cells indexed in {} ms; "
                                    + "4096 columns read in {} ms; {} address resolutions, {} index build",
                            buildNanos / 1_000_000.0, readNanos / 1_000_000.0, resolutions[0], builds[0]);
                });
            } finally {
                remove.run();
                Surveys.forget(fixture.player);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void grantsFollowTheCurrentColonyAndConnection(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            BlockPos start = fixture.chest.offset(600, 0, 0);
            JsonObject args = survey(fixture, start, start.east(10));
            ActionRegistry actions = ActionTools.create();
            ToolReply preview = actions.preview(fixture.player, "request_survey", args);
            ok(helper, actions.apply(fixture.player, "request_survey", args,
                    preview.data().get("revision").getAsString()).join());
            var other = Charters.found(fixture.player, "Other survey colony");
            try {
                fixture.player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(other.id()));
                ToolReply reply = ToolRegistry.READS.execute(fixture.player, "surveys", new JsonObject());
                helper.assertTrue(reply.data().getAsJsonArray("surveys").isEmpty(), "different colony has no grant");
                error(helper, readBlock(fixture, start), "outside_survey");
                fixture.player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(fixture.colony.id()));
                helper.assertTrue(Surveys.of(context(fixture)).size() == 1, "original colony retains its corridor");
                Charters.keep(fixture.colony, Charter.founded("Revoked", UUID.randomUUID()));
                error(helper, ToolRegistry.READS.execute(fixture.player, "surveys", new JsonObject()), "not_member");
                error(helper, readBlock(fixture, start), "not_member");
            } finally {
                Surveys.forget(fixture.player);
                other.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptBindingsQueueAndReadConfirmedSurveys(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        String points = "[" + xyz(fixture, fixture.chest) + ", " + xyz(fixture, fixture.chest.east(20)) + "]";
        String dimension = fixture.player.serverLevel().dimension().location().toString();
        var check = host.run(fixture.player,
                "set(\"surveys/corridor\", {\"dimension\": \"" + dimension + "\", \"points\": " + points + "})\n"
                + "result = ls(\"surveys\")", new JsonObject()).thenCompose(preview -> {
                    ok(helper, preview);
                    helper.assertTrue(preview.data().getAsJsonArray("result").toString().equals("[\"corridor\"]"),
                            "staged survey path is visible before filing");
                    helper.assertFalse(ActionBatch.autoApprove(preview, Set.of("surveys")),
                            "a destructive survey write always needs approval");
                    return host.decide(fixture.player, preview.data().get("revision").getAsString(), true);
                }).thenCompose(applied -> {
                    ok(helper, applied);
                    return host.run(fixture.player, "at = " + fixture.at(fixture.chest) + "\n"
                            + "result = [ls(\"surveys\"), colony_block(at), terrain(at, at, step=1)]", new JsonObject());
                }).thenAccept(reply -> {
                    ok(helper, reply);
                    var result = reply.data().getAsJsonArray("result");
                    helper.assertTrue(result.get(0).getAsJsonArray().size() == 1,
                            "script lists confirmed survey");
                    helper.assertTrue(result.get(1).getAsJsonObject().has("collision"), "script block read");
                    helper.assertTrue(result.get(2).getAsJsonObject().getAsJsonArray("columns").size() == 1,
                            "script terrain read");
                });
        AsyncWait.succeedWhen(helper, check, () -> { }, () -> {
            Surveys.forget(fixture.player);
            fixture.close();
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void scopeUsesMovingFrameAddresses(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            BlockPos origin = fixture.chest.offset(400, 0, 0);
            UUID id = UUID.randomUUID();
            WorldSpaces.Frame frame = new WorldSpaces.Frame() {
                @Override
                public UUID id() {
                    return id;
                }

                @Override
                public Level level() {
                    return helper.getLevel();
                }

                @Override
                public BlockPos storageOrigin() {
                    return origin;
                }

                @Override
                public Vec3 toWorld(Vec3 local) {
                    return local.add(Vec3.atLowerCornerOf(origin));
                }

                @Override
                public Vec3 toLocal(Vec3 world) {
                    return world.subtract(Vec3.atLowerCornerOf(origin));
                }
            };
            Runnable remove = WorldSpaces.install(new WorldSpaces.Source() {
                @Override
                public Optional<WorldSpaces.Frame> frame(Level level, UUID wanted) {
                    return level == frame.level() && wanted.equals(id) ? Optional.of(frame) : Optional.empty();
                }

                @Override
                public Optional<WorldSpaces.Frame> containing(Level level, BlockPos pos) {
                    return level == frame.level() && Math.abs((long) pos.getX() - origin.getX()) < 64
                            && pos.getY() >= origin.getY() ? Optional.of(frame) : Optional.empty();
                }

                @Override
                public Optional<WorldSpaces.Frame> aboard(Entity entity) {
                    return Optional.empty();
                }
            });
            try {
                WorldPos address = WorldPos.of(helper.getLevel(), origin);
                helper.assertTrue(address.realm().equals(new Realm.Frame(id)), "fixture uses local address");
                fixture.colony.hold(OWNER, new Claimed.Area(Set.of(address))).orElseThrow();
                Purity.assertPure(helper, "frame read scope", () -> {
                    helper.assertTrue(new ReadScope(context(fixture)).contains(helper.getLevel(), origin.above(8)),
                            "storage position converted before matching local held cell");
                    helper.assertFalse(new ReadScope(context(fixture)).contains(helper.getLevel(), BlockPos.ZERO),
                            "local coordinates do not grant dimension coordinates");
                });
                ReadScope scope = new ReadScope(context(fixture));
                helper.assertTrue(scope.column(new Realm.Frame(id), 0, 0)
                        .equals(List.of(new ReadScope.Interval(-8, 8))), "frame intervals use local coordinates");
                helper.assertTrue(scope.column(helper.getLevel(), origin.getX(), origin.getZ())
                        .equals(List.of(new ReadScope.Interval(origin.getY(), origin.getY() + 8))),
                        "storage intervals exclude heights outside the frame");
                JsonObject crossing = survey(fixture, origin, fixture.chest);
                error(helper, ActionTools.create().preview(fixture.player, "request_survey", crossing), "mixed_realms");
            } finally {
                remove.run();
            }
        }
        helper.succeed();
    }

    private static ToolContext context(ReadFixture fixture) {
        return new ToolContext(fixture.player, fixture.colony);
    }

    private static JsonObject xyz(ReadFixture fixture, BlockPos pos) {
        JsonObject point = fixture.at(pos);
        point.remove("dimension");
        return point;
    }

    private static JsonObject survey(ReadFixture fixture, BlockPos from, BlockPos to) {
        JsonObject args = new JsonObject();
        args.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        JsonArray points = new JsonArray();
        points.add(xyz(fixture, from));
        points.add(xyz(fixture, to));
        args.add("points", points);
        return args;
    }

    private static JsonObject box(ReadFixture fixture, BlockPos from, BlockPos to) {
        JsonObject args = new JsonObject();
        args.add("at", fixture.at(from));
        args.add("to", fixture.at(to));
        return args;
    }

    private static ToolReply readBlock(ReadFixture fixture, BlockPos pos) {
        JsonObject args = new JsonObject();
        args.add("at", fixture.at(pos));
        return ToolRegistry.READS.execute(fixture.player, "colony_block", args);
    }

    private static JsonObject column(GameTestHelper helper, ReadFixture fixture, BlockPos from, BlockPos to) {
        ToolReply reply = ToolRegistry.READS.execute(fixture.player, "terrain", box(fixture, from, to));
        ok(helper, reply);
        return reply.data().getAsJsonArray("columns").get(0).getAsJsonObject();
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), reply.data().toString());
    }

    private static void error(GameTestHelper helper, ToolReply reply, String expected) {
        helper.assertTrue(reply.data().has("error") && reply.data().get("error").getAsString().equals(expected),
                expected + ": " + reply.data());
    }
}
