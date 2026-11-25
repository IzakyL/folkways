package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.Guide;
import io.github.izakyl.folkways.assistant.tool.internal.MechanicsReads;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ToolText;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.mechanics.api.Mechanics;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.plan.internal.Why;
import io.github.izakyl.folkways.core.script.api.Profile;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.script.api.ScriptValues;
import io.github.izakyl.folkways.core.shell.api.OptionalIntegrations;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.netty.buffer.Unpooled;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class MechanicsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(MechanicsGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void catalogueIsPureAndIndependentOfColonyMembership(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
            logged(fixture, () -> {
                Purity.assertPure(helper, "static catalogue", () -> {
                    var index = read(fixture, null, null, null);
                    ok(helper, index);
                    helper.assertTrue(index.data().getAsJsonArray("topics").contains(
                            new JsonPrimitive("workshops")),
                            "the index includes colony workshops");
                    helper.assertTrue(index.data().get("patterns").getAsString().equals("patterns()"),
                            "existing pattern API is referenced");
                    var stations = read(fixture, "stations", null, null);
                    Set<String> ids = ids(stations);
                    helper.assertTrue(ids.containsAll(Set.of("minecraft:crafting_table", "minecraft:stonecutter",
                            "minecraft:smithing_table", "minecraft:furnace", "minecraft:blast_furnace",
                            "minecraft:smoker", "minecraft:composter")), "main vanilla station types: " + ids);
                    helper.assertFalse(stations.data().has("colony_id"), "catalogue has no colony capture");
                    helper.assertTrue(ids(read(fixture, "vocations", null, null)).containsAll(Set.of(
                            "folkways_vanilla:crafting", "folkways_vanilla:building", "folkways_vanilla:farming", "folkways_vanilla:fishing",
                            "folkways_vanilla:herding", "folkways_core:hauling", "folkways_vanilla:patrolling")), "registered trades");
                    var zones = read(fixture, "zones", null, null);
                    helper.assertTrue(ids(zones).containsAll(Set.of("folkways_vanilla:farm", "folkways_vanilla:ground",
                            "folkways_vanilla:patrol", "folkways_vanilla:pasture", "folkways_vanilla:apiary", "folkways_vanilla:dining")),
                            "registered drawing kinds");
                    var patrol = entry(zones, "folkways_vanilla:patrol");
                    helper.assertTrue(patrol.get("shape").getAsString().equals("path")
                            && patrol.get("limit").getAsInt() >= 2, "path bounds come from registration");
                });
                helper.assertTrue(ReadTools.create().execute(fixture.player, "colony_overview", new JsonObject())
                        .data().get("error").getAsString().equals("not_member"), "member reads still refuse");
                return null;
            });
            error(helper, read(fixture, "stations", null, null), "not_logged_in");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void workshopReadFollowsPlayerFacilityPublication(GameTestHelper helper) {
        var fixture = new ReadFixture(helper);
        Cleanup.on(helper, fixture::close);
        var level = helper.getLevel();
        var furnace = new BlockPos(0, 1, 0);
        helper.setBlock(furnace, Blocks.FURNACE);
        var at = WorldPos.of(level, helper.absolutePos(furnace));
        var extent = new FacilityExtent(at.realm(), at.cell(), at.cell());
        var facilities = DefinedFacilities.of(fixture.colony).orElseThrow();
        String name = "Fixture smelter";
        var draft = facilities.preview(name, Optional.of(Map.of(
            "name", name, "extent", DefinitionCodec.extent(extent),
            "controls", Map.of(
                "in", Map.of("part", "folkways_core:item/up", "offset", List.of(0, 0, 0)),
                "out", Map.of("part", "folkways_core:item/down", "offset", List.of(0, 0, 0))),
            "routines", Map.of("smelt", Map.of("source", """
                def recipes(input):
                    return [row for row in recipes_of("minecraft:smelting")
                            if row["id"] == "minecraft:iron_ingot_from_smelting_raw_iron"]

                def step(state, input, args):
                    return state, [done]
                """)),
            "workshop", Map.of("routine", "smelt"))));
        helper.assertTrue(draft.problems().isEmpty(), "valid processing facility: " + draft.problems());
        helper.assertTrue(facilities.apply(draft).isEmpty(), "facility is published");
        var id = facilities.facilities().getFirst().id();
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(
            Mechanics.workshops(fixture.colony, level.getServer()).stream()
                .filter(workshop -> workshop.id().equals(id)).flatMap(workshop -> workshop.facility().stream())
                .anyMatch(details -> details.explicitRecipes().size() == 1),
            "the worker publishes the selected recipe")).thenExecute(() -> logged(fixture, () -> {
                var reply = read(fixture, "workshops", "fixture smelter", null);
                ok(helper, reply);
                var row = entry(reply, id.toString());
                helper.assertTrue(reply.texts().stream().anyMatch(text -> text.getString().equals(name))
                        && row.get("facility").getAsString().equals(id.toString())
                        && row.getAsJsonArray("recipes").size() == 1,
                    "the workshop exposes its name, facility and selected recipe");
                helper.assertTrue(facilities.apply(facilities.preview(name, Optional.empty())).isEmpty(), "facility is removed");
                helper.assertFalse(ids(read(fixture, "workshops", null, null)).contains(id.toString()),
                    "withdrawing the facility clears the workshop entry");
                return null;
            })).thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void breadRecipesExposeInputsStationTradeAndConditions(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            logged(fixture, () -> {
                Purity.assertPure(helper, "bread recipes", () -> {
                    var reply = read(fixture, "recipes", "minecraft:bread", null);
                    ok(helper, reply);
                    var bread = entry(reply, "minecraft:bread");
                    var station = bread.getAsJsonObject("station");
                    helper.assertTrue(station.get("id").getAsString().equals("minecraft:crafting_table"),
                            "bread uses a crafting table");
                    helper.assertTrue(station.getAsJsonArray("vocations").toString().contains("folkways_vanilla:crafting"),
                            "crafting trade is required");
                    helper.assertTrue(bread.getAsJsonArray("inputs").toString().contains("minecraft:wheat")
                            && bread.getAsJsonArray("outputs").toString().contains("minecraft:bread"),
                            "one recipe step includes both sides");
                    helper.assertTrue(ids(read(fixture, "stations", "minecraft:bread", null))
                            .contains("minecraft:crafting_table"), "item query locates station types");
                    helper.assertTrue(ids(read(fixture, null, "Bread", null)).contains("minecraft:bread"),
                            "query alone selects recipes; names ignore case");
                    helper.assertTrue(read(fixture, "recipes", "minecraft:crafting_table", 1)
                            .data().get("truncated").getAsBoolean(), "station query respects entry limit");
                    var smithing = read(fixture, "recipes", "minecraft:netherite_pickaxe", null);
                    var upgrade = entry(smithing, "minecraft:netherite_pickaxe_smithing");
                    helper.assertTrue(upgrade.getAsJsonArray("inputs").size() == 3
                            && upgrade.getAsJsonObject("station").get("id").getAsString()
                                    .equals("minecraft:smithing_table"),
                            "smithing includes template, base and addition");
                    helper.assertTrue(read(fixture, "recipes", "#minecraft:planks", 1)
                            .data().getAsJsonArray("entries").toString().contains("#minecraft:planks"),
                            "ingredient tags are preserved");
                    var furnace = read(fixture, "stations", "minecraft:furnace", null);
                    helper.assertTrue(Component.Serializer.toJson(furnace.texts().getLast(),
                            fixture.player.registryAccess()).contains("folkways.mechanics.fuel"),
                            "furnace fuel is described by a translated condition");
                });
                return null;
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void optionalCookeryPublishesItsHeatSources(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            logged(fixture, () -> {
                Purity.assertPure(helper, "cookery catalogue", () -> {
                    var reply = read(fixture, "stations", "kaleidoscope_cookery:stockpot", null);
                    if (OptionalIntegrations.COOKERY.enabled()) {
                        var pot = entry(reply, "kaleidoscope_cookery:stockpot");
                        helper.assertTrue(pot.getAsJsonArray("recipe_types").size() == 2,
                                "fixed and flexible native recipes are registered");
                        helper.assertTrue(reply.texts().stream().anyMatch(text -> Component.Serializer.toJson(
                                text, fixture.player.registryAccess()).contains("folkways.mechanics.heat")),
                                "cookery heat-source condition is preserved for client localization");
                        helper.assertFalse(read(fixture, "recipes", "kaleidoscope_cookery:stockpot", 2)
                                .data().getAsJsonArray("entries").isEmpty(), "native pot recipes are generated");
                    } else {
                        helper.assertTrue(reply.data().getAsJsonArray("entries").isEmpty(),
                                "absent plugins contribute no station types");
                    }
                });
                return null;
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void invalidArgumentsAndBoundsAreRejected(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            logged(fixture, () -> {
                Purity.assertPure(helper, "catalogue refusals", () -> {
                    for (int limit : List.of(-1, 0, 129, Integer.MAX_VALUE)) {
                        error(helper, read(fixture, "recipes", null, limit), "invalid_arguments");
                    }
                    error(helper, read(fixture, "unknown", null, null), "invalid_arguments");
                    error(helper, read(fixture, "recipes", "x".repeat(257), null), "invalid_arguments");
                    JsonObject world = new JsonObject();
                    world.addProperty("topic", "stations");
                    world.addProperty("at", "minecraft:overworld");
                    error(helper, MechanicsReads.read(fixture.player, world), "invalid_arguments");
                    error(helper, read(fixture, "recipes", " ".repeat(257), null), "invalid_arguments");
                    JsonObject overflow = new JsonObject();
                    overflow.addProperty("limit", 4294967297L);
                    error(helper, MechanicsReads.read(fixture.player, overflow), "invalid_arguments");
                    JsonObject fractional = new JsonObject();
                    fractional.addProperty("limit", 1.5);
                    error(helper, MechanicsReads.read(fixture.player, fractional), "invalid_arguments");
                    helper.assertTrue(read(fixture, "recipes", "folkways_assistant:no_such_item", null)
                            .data().getAsJsonArray("entries").isEmpty(), "unknown queries are empty");
                });
                return null;
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void trimmingKeepsRowsAndTextReferencesWithinWireBudget(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            List<Function<ToolText, JsonObject>> rows = new ArrayList<>();
            for (int index = 0; index < 128; index++) {
                rows.add(text -> {
                    JsonObject row = new JsonObject();
                    row.add("name", text.text(Component.literal("机".repeat(1000))));
                    return row;
                });
            }
            Purity.assertPure(helper, "catalogue byte trimming", () -> {
                var reply = MechanicsReads.trimmed(fixture.player, "stations", rows, 128, 24 * 1024);
                int kept = reply.data().getAsJsonArray("entries").size();
                helper.assertTrue(kept > 0 && kept < rows.size(), "retain a useful UTF-8 prefix");
                helper.assertTrue(reply.data().get("truncated").getAsBoolean(), "byte clipping is explicit");
                helper.assertTrue(reply.texts().size() == kept, "discarded text references do not leak");
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(),
                        fixture.player.registryAccess());
                try {
                    reply.encode(buffer);
                    helper.assertTrue(buffer.readableBytes() < ToolReply.MAX_BYTES, "under transport cap");
                    helper.assertTrue(ToolReply.decode(buffer).localized().getAsJsonArray("entries")
                            .get(kept - 1).getAsJsonObject().get("name").getAsString().equals("机".repeat(1000)),
                            "references survive encoding and trimming");
                } finally {
                    buffer.release();
                }
            });
            logged(fixture, () -> {
                var reply = read(fixture, "recipes", null, 128);
                helper.assertTrue(reply.data().get("truncated").getAsBoolean(), "large recipe list is trimmed");
                ok(helper, reply);
                return null;
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void starlarkBindingsUseOptionalDefaultsAndStayPure(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            logged(fixture, () -> {
                Purity.assertPure(helper, "mechanics Starlark", () -> {
                    var bindings = ScriptApi.bindings(false, (function, args, thread) -> {
                        ToolReply reply = MechanicsReads.read(fixture.player, args);
                        return ScriptValues.readOutput(reply.data(), reply.texts(), thread.mutability());
                    });
                    var scripts = new ScriptRuntime(Profile.agent().withBindings(bindings)
                            .withLimits(10_000, Duration.ofSeconds(10), 0));
                    try {
                        var loaded = scripts.load(ResourceLocation.parse("folkways_assistant:mechanics_test"),
                                "result = mechanics(None, query=\"minecraft:bread\", limit=None)",
                                ignored -> Optional.empty());
                        var values = ScriptValues.output(ToolReply.MAX_BYTES);
                        JsonObject data = new JsonObject();
                        data.add("result", values.write(loaded.module().getGlobal("result")));
                        var reply = new ToolReply(data, values.texts());
                        helper.assertTrue(reply.data().toString().contains("minecraft:bread"),
                                "script None values use defaults");
                        var large = scripts.load(ResourceLocation.parse("folkways_assistant:mechanics_large_test"),
                                "result = mechanics(\"recipes\", limit=128)", ignored -> Optional.empty());
                        values = ScriptValues.output(ToolReply.MAX_BYTES);
                        data = new JsonObject();
                        data.add("result", values.write(large.module().getGlobal("result")));
                        reply = new ToolReply(data, values.texts()).capped(fixture.player.registryAccess());
                        ok(helper, reply);
                        helper.assertTrue(reply.data().getAsJsonObject("result").get("truncated").getAsBoolean(),
                                "large catalogue also fits the complete script reply");
                        helper.assertTrue(ScriptApi.help("mechanics", false).contains("128")
                                && ScriptApi.run(false).description().contains("plan_*"), "API help is discoverable");
                    } catch (Exception failure) {
                        helper.fail(failure.toString());
                    }
                });
                return null;
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void guideChaptersLocalizeAndFallbackIndividually(GameTestHelper helper) {
        Purity.assertPure(helper, "packaged guide", () -> {
            String english = ScriptApi.help("guide", false, "en_us");
            String chinese = ScriptApi.help("guide", false, "zh_cn");
            helper.assertTrue(english.contains("guide:pastures — Pastures")
                    && chinese.contains("guide:pastures — 牧场"), "stable slugs with localized headings");
            String chapter = ScriptApi.help("guide:pastures", false, "zh_cn");
            helper.assertTrue(chapter.contains("成年牲畜") && !chapter.contains("## 工种"),
                    "one second-level section includes only its body");
            helper.assertTrue(ScriptApi.help("guide:pastures", false, "fr_fr")
                    .equals(ScriptApi.help("guide:pastures", false, "en_us")), "unbundled languages use English");
            helper.assertTrue(english.contains("guide:groundwork-fallen-logs")
                    && english.contains("guide:facilities — Assistant facilities")
                    && chinese.contains("guide:facilities — 助手操作设施"), "gameplay and tool chapters share one index");
            var source = Guide.split("# Guide\n\n## One\nfirst\n### Detail\nkept\n```md\n## Code\n```\n"
                    + "## Two\nsecond\n");
            helper.assertTrue(source.size() == 2 && source.getFirst().body().contains("## Code"),
                    "fenced headings and third-level headings remain in their section");
            var translated = Guide.split("## 第一\n<!-- guide:one -->\n中文\n## Two\n");
            var fallback = Guide.localized(source, translated);
            helper.assertTrue(fallback.getFirst().title().equals("第一")
                    && fallback.getLast().body().equals("second"), "blank Chinese chapter falls back per slug");
            helper.assertTrue(Guide.localized(source, List.of()).equals(source), "missing chapter falls back");
            helper.assertTrue(ScriptApi.help("guide:../../server.properties", false).startsWith("Unknown guide"),
                    "guide names cannot read arbitrary resources");
            helper.assertTrue(ScriptApi.help("guide:pastures", false, "zh_cn").contains("成年牲畜"),
                    "help text uses the selected language");
        });
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void discoveredPartGuidesCoverBothLanguages(GameTestHelper helper) {
        // Optional native adapters are not all loaded together in this suite.
        String entity = "12345678-1234-1234-1234-123456789abc";
        List<String> parts = new ArrayList<>(List.of("lever", "button", "folkways_core:item", "folkways_core:fluid",
                "folkways_core:energy", "turn", "motion", "level", "aim", "fire", "breech", "ammo", "muzzle", "ram", "destination",
                "park", "height", "lean", "limb/1", "limb/12", "key/use", "key/attack", "key/drop", "key/inventory", "input",
                "destination/" + entity, "cargo/" + entity, "aim/" + entity, "fire/" + entity,
                "ammo/" + entity, "breech/" + entity, "plowing/" + entity, "tools/" + entity));
        for (var side : net.minecraft.core.Direction.values()) {
            parts.add("folkways_core:item/" + side.getName());
        }
        for (String part : parts) {
            for (String language : List.of("en_us", "zh_cn")) {
                helper.assertTrue(Guide.hasPart(part, language), "missing part entry for " + part + " in " + language);
            }
        }
        String topic = "parts/fire/" + entity;
        helper.assertTrue(ScriptApi.help(topic, false, "en_us").contains("native trigger")
                && ScriptApi.help(topic, false, "zh_cn").contains("原生扳机"), "help expands a concrete UUID topic");
        helper.assertTrue(ScriptApi.help("parts/../../server.properties", false).startsWith("Unknown part"),
                "part topics cannot read arbitrary resources");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void troubleshootingExplainsEveryPlanCode(GameTestHelper helper) {
        List<String> codes = new ArrayList<>();
        for (Unassigned.Reason reason : Unassigned.Reason.values()) {
            codes.add(reason.name());
        }
        for (Class<?> why : Why.class.getPermittedSubclasses()) {
            codes.add(why.getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
        }
        for (String language : List.of("en_us", "zh_cn")) {
            String chapter = ScriptApi.help("troubleshooting", false, language);
            List<String> missing = codes.stream().filter(code -> !chapter.contains("`" + code + "`")).toList();
            helper.assertTrue(missing.isEmpty(), language + " troubleshooting misses " + missing);
            helper.assertTrue(ScriptApi.help("concepts", false, language).contains("`stalled`"),
                    language + " concepts chapter is reachable");
        }
        helper.succeed();
    }

    private static ToolReply read(ReadFixture fixture, String topic, String query, Integer limit) {
        JsonObject args = new JsonObject();
        if (topic != null) {
            args.addProperty("topic", topic);
        }
        if (query != null) {
            args.addProperty("query", query);
        }
        if (limit != null) {
            args.addProperty("limit", limit);
        }
        return MechanicsReads.read(fixture.player, args);
    }

    private static Set<String> ids(ToolReply reply) {
        return reply.data().getAsJsonArray("entries").asList().stream()
                .map(value -> value.getAsJsonObject().get("id").getAsString()).collect(Collectors.toSet());
    }

    private static JsonObject entry(ToolReply reply, String id) {
        return reply.data().getAsJsonArray("entries").asList().stream().map(value -> value.getAsJsonObject())
                .filter(value -> value.get("id").getAsString().equals(id)).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T logged(ReadFixture fixture, Supplier<T> work) {
        List<ServerPlayer> players;
        try {
            var field = PlayerList.class.getDeclaredField("players");
            field.setAccessible(true);
            players = (List<ServerPlayer>) field.get(fixture.player.getServer().getPlayerList());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
        players.add(fixture.player);
        try {
            return work.get();
        } finally {
            players.remove(fixture.player);
        }
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), "read succeeds: " + reply.data());
    }

    private static void error(GameTestHelper helper, ToolReply reply, String code) {
        helper.assertTrue(reply.data().has("error") && reply.data().get("error").getAsString().equals(code),
                "expected " + code + ": " + reply.data());
    }
}
