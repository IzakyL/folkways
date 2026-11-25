package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.WorkDefaults;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.operation.Commissioning;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.core.front.engine.colony.ZoneDrafts;
import io.github.izakyl.folkways.core.front.engine.operation.ResidentChanges;
import io.github.izakyl.folkways.core.front.engine.operation.SettingChanges;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ActionGameTests {

    private ActionGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void everyPreviewIsPureAndBothPhasesRequireMembership(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var queries = queries(fixture);
            for (var entry : queries.entrySet()) {
                ActionRegistry registry = ActionTools.create();
                Purity.assertPure(helper, "preview " + entry.getKey(), () -> {
                    ToolReply reply = registry.preview(fixture.player, entry.getKey(), entry.getValue());
                    helper.assertFalse(reply.data().has("error"), entry.getKey() + " " + reply.data());
                    helper.assertTrue(!reply.texts().isEmpty(), "summary stays a Component");
                    String summary = Component.Serializer.toJson(reply.component("summary"),
                            fixture.player.registryAccess());
                    helper.assertFalse(summary.contains("resident_id") || summary.contains("zone_or_path")
                            || summary.contains("holding_id") || summary.contains(fixture.person.getUUID().toString()),
                            "player preview contains words rather than transport arguments: " + entry.getKey());
                });
            }
            helper.assertTrue(queries.size() == ActionRegistry.ACTIONS.specs().size(), "every ACT has a fixture");
            for (var entry : queries.entrySet()) {
                ActionRegistry registry = ActionTools.create();
                var preview = registry.preview(fixture.player, entry.getKey(), entry.getValue());
                Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
                Purity.assertPure(helper, "revoked " + entry.getKey(), () -> {
                    error(helper, registry.preview(fixture.player, entry.getKey(), entry.getValue()), "not_member");
                    error(helper, registry.apply(fixture.player, entry.getKey(), entry.getValue(),
                            preview.data().get("revision").getAsString()).join(), "not_member");
                });
                Charters.keep(fixture.colony, Charter.founded("Assistant fixture", fixture.player.getUUID()));
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void everyActionRejectsStaleAndTamperedRevisions(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            for (var entry : queries(fixture).entrySet()) {
                ActionRegistry registry = ActionTools.create();
                var preview = registry.preview(fixture.player, entry.getKey(), entry.getValue());
                String revision = preview.data().get("revision").getAsString();
                Charters.keep(fixture.colony, Charter.founded("Changed " + entry.getKey(), fixture.player.getUUID()));
                Purity.assertPure(helper, "stale " + entry.getKey(), () -> {
                    error(helper, registry.apply(fixture.player, entry.getKey(), entry.getValue(), revision).join(),
                            "stale");
                    error(helper, registry.apply(fixture.player, entry.getKey(), entry.getValue(), revision + "x")
                            .join(), "stale");
                });
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedTargetsAndExcludedOperationsNeverApply(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var queries = queries(fixture);
            for (String tool : List.of("create_zone", "create_path", "commission_build")) {
                JsonObject args = queries.get(tool).deepCopy();
                JsonObject far = fixture.at(new BlockPos(20_000_000, 64, 20_000_000));
                switch (tool) {
                    case "commission_build" -> args.add("at", far);
                    case "create_zone" -> {
                        far.remove("dimension");
                        args.add("from", far);
                        args.add("to", far);
                    }
                    case "create_path" -> {
                        far.remove("dimension");
                        JsonArray points = new JsonArray();
                        points.add(far);
                        points.add(far.deepCopy());
                        args.add("points", points);
                    }
                    default -> throw new AssertionError(tool);
                }
                Purity.assertPure(helper, "unloaded " + tool, () -> {
                    error(helper, ActionTools.create().preview(fixture.player, tool, args), "unloaded");
                });
            }
            var kind = ResourceLocation.parse(queries.get("create_zone").get("kind").getAsString());
            var farHolding = fixture.colony.hold(kind, new Claimed.Area(Set.of(WorldPos.of(helper.getLevel().dimension(),
                    new BlockPos(20_000_000, 64, 20_000_000))))).orElseThrow();
            JsonObject erase = new JsonObject();
            erase.addProperty("id", farHolding.id().toString());
            JsonObject setting = new JsonObject();
            setting.addProperty("scope", kind.toString());
            setting.addProperty("zone_or_path", farHolding.id().toString());
            setting.addProperty("key", "unknown");
            setting.addProperty("value", 1);
            Purity.assertPure(helper, "unloaded drawings", () -> {
                error(helper, ActionTools.create().preview(fixture.player, "erase_zone_or_path", erase), "unloaded");
                error(helper, ActionTools.create().preview(fixture.player, "set_setting", setting), "unloaded");
            });
            var registry = ActionTools.create();
            var rename = queries.get("rename_resident");
            var preview = registry.preview(fixture.player, "rename_resident", rename);
            fixture.person.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            Purity.assertPure(helper, "resident unloaded after preview", () -> {
                error(helper, registry.apply(fixture.player, "rename_resident", rename,
                        preview.data().get("revision").getAsString()).join(), "unloaded");
                error(helper, registry.preview(fixture.player, "set_work", queries.get("set_work")), "unloaded");
            });
            for (String excluded : List.of("raze_colony", "invite", "upload_blueprint", "creative_place")) {
                helper.assertTrue(ActionTools.create().preview(fixture.player, excluded, new JsonObject())
                        .data().has("error"), "explicitly excluded " + excluded);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void applyingMatchesTheSharedBookOperations(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var queries = queries(fixture);
            var registry = ActionTools.create();
            String vocation = queries.get("set_work").get("vocation").getAsString();
            var body = fixture.person;
            var trade = Vocations.of(ResourceLocation.parse(vocation)).orElseThrow();
            ResidentChanges.work(body, trade, (held, known) -> held.setKeenness(known, Keenness.FOURTH));
            var expected = body.licences().serializeNBT(fixture.player.registryAccess());
            ResidentChanges.work(body, trade, (held, known) -> held.setKeenness(known, Keenness.FIRST));
            apply(helper, registry, fixture, "set_work", queries.get("set_work"));
            helper.assertTrue(expected.equals(body.licences().serializeNBT(fixture.player.registryAccess())),
                    "work matches the workforce operation");
            ResidentChanges.rename(body, "After");
            Component name = body.getCustomName();
            ResidentChanges.rename(body, "Before");
            apply(helper, registry, fixture, "rename_resident", queries.get("rename_resident"));
            helper.assertTrue(name.equals(body.getCustomName()), "rename matches workforce operation");
            WorkDefaults.set(fixture.colony, PersonBody.ID, trade.id(), true, 4);
            var defaultRule = WorkDefaults.of(fixture.colony, PersonBody.ID).of(trade.id());
            WorkDefaults.set(fixture.colony, PersonBody.ID, trade.id(), true, 1);
            apply(helper, registry, fixture, "set_work_default", queries.get("set_work_default"));
            helper.assertTrue(defaultRule.equals(WorkDefaults.of(fixture.colony, PersonBody.ID).of(trade.id())),
                    "defaults match the defaults page operation");
            var orders = ResourceLocation.parse("folkways_vanilla:orders");
            SettingChanges.set(fixture.colony, orders, Optional.empty(), "count", new ColonySettings.Value.Count(7));
            var settings = ColonyFront.of(fixture.colony).settings(orders);
            SettingChanges.set(fixture.colony, orders, Optional.empty(), "count", new ColonySettings.Value.Count(1));
            apply(helper, registry, fixture, "set_setting", queries.get("set_setting"));
            helper.assertTrue(settings.equals(ColonyFront.of(fixture.colony).settings(orders)),
                    "settings match the settings page operation");
            var zoneArgs = queries.get("create_zone");
            ResourceLocation zoneKind = ResourceLocation.parse(zoneArgs.get("kind").getAsString());
            BlockPos corner = fixture.chest.offset(0, 0, 4);
            var directZone = ZoneDrafts.zone(helper.getLevel(), fixture.colony, zoneKind, corner, corner,
                    ColonySettings.empty()).orElseThrow();
            var expectedCells = directZone.cells();
            ColonyFront.of(fixture.colony).erase(directZone.id());
            var made = apply(helper, registry, fixture, "create_zone", zoneArgs);
            UUID madeId = UUID.fromString(made.data().get("holding_id").getAsString());
            helper.assertTrue(ColonyFront.of(fixture.colony).zone(madeId).orElseThrow().cells().equals(expectedCells),
                    "zone matches ZoneDrafts");
            var eraseArgs = new JsonObject();
            eraseArgs.addProperty("id", madeId.toString());
            apply(helper, registry, fixture, "erase_zone_or_path", eraseArgs);
            helper.assertTrue(fixture.colony.claim(madeId).isEmpty(), "erase matches the book operation");
            var pathArgs = queries.get("create_path");
            var pathKind = ResourceLocation.parse(pathArgs.get("kind").getAsString());
            var points = List.of(corner, corner.east());
            var directPath = ZoneDrafts.path(helper.getLevel(), fixture.colony, pathKind, points,
                    ColonySettings.empty()).orElseThrow();
            ColonyFront.of(fixture.colony).erase(directPath.id());
            var madePath = apply(helper, registry, fixture, "create_path", pathArgs);
            helper.assertTrue(ColonyFront.of(fixture.colony).path(UUID.fromString(madePath.data()
                    .get("holding_id").getAsString())).orElseThrow().points().equals(points),
                    "path matches ZoneDrafts");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void declarativeActionsReportNoChangeOnRepeat(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var queries = queries(fixture);
            ActionRegistry registry = ActionTools.create();
            for (String tool : List.of("set_setting", "set_work", "set_work_default", "rename_resident")) {
                apply(helper, registry, fixture, tool, queries.get(tool));
                var repeat = registry.preview(fixture.player, tool, queries.get(tool));
                Purity.assertPure(helper, "unchanged " + tool, () -> {
                    var result = registry.apply(fixture.player, tool, queries.get(tool),
                            repeat.data().get("revision").getAsString()).join();
                    helper.assertFalse(result.data().get("changed").getAsBoolean(), "no change for " + tool);
                });
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void survivalCommissionUsesTheNativeOperation(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        var args = queries(fixture).get("commission_build");
        args.add("dimension", args.getAsJsonObject("at").remove("dimension"));
        ScriptHost host = new ScriptHost();
        var result = host.run(fixture.player, "set(\"builds/native\", " + args + ")", new JsonObject())
                .thenCompose(preview -> {
                    helper.assertFalse(preview.data().has("error"), "commission preview " + preview.data());
                    return host.decide(fixture.player, preview.data().get("revision").getAsString(), true);
                });
        var direct = result.thenCompose(reply -> Commissioning.source().orElseThrow()
                .commission(fixture.player, helper.getLevel(), fixture.colony,
                new Commissioning.Request(ResourceLocation.parse("folkways_assistant:assistant_test"),
                        fixture.chest.offset(0, 0, 4),
                        fixture.chest.offset(0, 0, 4), Direction.NORTH, "none")));
        AsyncWait.succeedWhen(helper, CompletableFuture.allOf(result, direct), () -> {
            helper.assertTrue(result.join().data().getAsJsonArray("applied").get(0).getAsJsonObject()
                    .get("changed").getAsBoolean() == direct.join().filed()
                    && direct.join().filed(), "survival path filed the same native order");
            helper.assertTrue(helper.getLevel().getBlockState(fixture.chest.offset(0, 0, 4)).isAir(),
                    "commission did not place creative blocks");
        }, fixture::close);
    }

    static Map<String, JsonObject> queries(ReadFixture fixture) {
        var queries = new LinkedHashMap<String, JsonObject>();
        var zoneKind = Enrollments.delegations().stream().filter(entry -> entry.shape() instanceof Shape.Volume
                && entry.disabledReason().isEmpty())
                .findFirst().orElseThrow().id();
        var pathKind = Enrollments.delegations().stream().filter(entry -> entry.shape() instanceof Shape.Path
                && entry.disabledReason().isEmpty())
                .findFirst().orElseThrow().id();
        var vocation = Vocations.all().stream().filter(trade -> fixture.person.licences().trade(trade).isPresent())
                .findFirst().orElseThrow().id();
        JsonObject setting = new JsonObject();
        setting.addProperty("scope", "folkways_vanilla:orders");
        setting.addProperty("key", "count");
        setting.addProperty("value", 7);
        queries.put("set_setting", setting);
        JsonObject work = new JsonObject();
        work.addProperty("resident_id", fixture.person.getUUID().toString());
        work.addProperty("vocation", vocation.toString());
        work.addProperty("keenness", 4);
        queries.put("set_work", work);
        JsonObject defaults = work.deepCopy();
        defaults.remove("resident_id");
        defaults.addProperty("resident_kind", PersonBody.ID.toString());
        queries.put("set_work_default", defaults);
        JsonObject rename = new JsonObject();
        rename.addProperty("resident_id", fixture.person.getUUID().toString());
        rename.addProperty("name", "After");
        queries.put("rename_resident", rename);
        BlockPos corner = fixture.chest.offset(0, 0, 4);
        JsonObject point = fixture.at(corner);
        point.remove("dimension");
        JsonObject zone = new JsonObject();
        zone.addProperty("kind", zoneKind.toString());
        zone.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
        zone.add("from", point);
        zone.add("to", point.deepCopy());
        queries.put("create_zone", zone);
        JsonObject path = new JsonObject();
        path.addProperty("kind", pathKind.toString());
        path.addProperty("dimension", zone.get("dimension").getAsString());
        JsonArray points = new JsonArray();
        points.add(point.deepCopy());
        JsonObject second = fixture.at(corner.east());
        second.remove("dimension");
        points.add(second);
        path.add("points", points);
        queries.put("create_path", path);
        JsonObject survey = path.deepCopy();
        survey.remove("kind");
        queries.put("request_survey", survey);
        var drawn = ZoneDrafts.zone(fixture.player.serverLevel(), fixture.colony, zoneKind, corner.north(),
                corner.north(), ColonySettings.empty()).orElseThrow();
        JsonObject erase = new JsonObject();
        erase.addProperty("id", drawn.id().toString());
        queries.put("erase_zone_or_path", erase);
        JsonObject build = new JsonObject();
        build.addProperty("pattern", "folkways_assistant:assistant_test");
        build.add("at", fixture.at(corner));
        queries.put("commission_build", build);
        return queries;
    }

    private static ToolReply apply(GameTestHelper helper, ActionRegistry registry, ReadFixture fixture, String tool,
            JsonObject args) {
        ToolReply preview = registry.preview(fixture.player, tool, args);
        helper.assertFalse(preview.data().has("error"), "preview " + tool + preview.data());
        var result = registry.apply(fixture.player, tool, args, preview.data().get("revision").getAsString()).join();
        helper.assertFalse(result.data().has("error"), "apply " + tool + result.data());
        Purity.assertPure(helper, "single use revision", () -> helper.assertTrue(registry.apply(fixture.player, tool,
                args, preview.data().get("revision").getAsString()).join().data().has("error"), "replay refused"));
        return result;
    }

    private static void error(GameTestHelper helper, ToolReply reply, String code) {
        helper.assertTrue(reply.data().has("error") && reply.data().get("error").getAsString().equals(code),
                "expected " + code + ", got " + reply.data());
    }
}
