package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.client.SessionGameTests;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.assistant.tool.internal.LaborReasons;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.ToolText;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.runtime.LaborCensus;
import io.github.izakyl.folkways.core.runtime.LaborSnapshot;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.ReservedInput;
import io.github.izakyl.folkways.core.work.internal.HoldingRegistry;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.PersonContent;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ReadToolsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ReadToolsGameTests.class);
        event.register(AgentIntegrationGameTests.class);
        event.register(ActionGameTests.class);
        event.register(ScriptGameTests.class);
        event.register(PlanGameTests.class);
        event.register(SurveyGameTests.class);
        event.register(RailGameTests.class);
        event.register(DraftGameTests.class);
        event.register(DiagnosticGameTests.class);
        event.register(SessionGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void everyReadIsPureColdAndWarm(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            for (String pass : List.of("cold", "warm")) {
                if (pass.equals("cold")) {
                    ColonyFront.forget(fixture.colony.id());
                }
                for (ToolSpec tool : ToolRegistry.READS.specs()) {
                    JsonObject args = arguments(fixture, tool.id());
                    Purity.assertPure(helper, pass + " " + tool.id(), () -> {
                        ToolReply reply = ReadTools.create().execute(fixture.player, tool.id(), args);
                        helper.assertTrue(!reply.data().has("error") || tool.id().equals("plan_node")
                                && reply.data().get("error").getAsString().equals("unknown_node"),
                                "tool succeeded or node has no captured plan: " + tool.id() + reply.data());
                        helper.assertTrue(reply.data().has("capture_tick"), "result has capture tick");
                    });
                }
            }
            JsonObject target = new JsonObject();
            target.add("at", fixture.at(fixture.chest));
            Purity.assertPure(helper, "loaded block target", () -> {
                var reply = ReadTools.create().execute(fixture.player, "look_target", target);
                helper.assertFalse(reply.data().has("error"), "loaded held target described");
                helper.assertTrue(reply.data().has("facility_id"), "target identifies its held facility");
            });
            ToolReply stock = ReadTools.create().execute(fixture.player, "stock", new JsonObject());
            helper.assertFalse(stock.data().get("complete").getAsBoolean(), "unopened loot remains unknown");
            helper.assertTrue(((ChestBlockEntity) helper.getLevel().getBlockEntity(fixture.chest)).getLootTable()
                    != null, "stock never opens loot");
            helper.assertTrue(ReadTools.create().execute(fixture.player, "colony_problems", new JsonObject())
                    .data().getAsJsonArray("sites").size() > 0, "generic site descriptions include housing");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void everyReadRejectsNonMembersAndRechecksMembership(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            Charters.keep(fixture.colony, Charter.founded("Someone else's", UUID.randomUUID()));
            for (ToolSpec tool : ToolRegistry.READS.specs()) {
                Purity.assertPure(helper, "unauthorized " + tool.id(), () -> {
                    ToolReply reply = ReadTools.create().execute(fixture.player, tool.id(),
                            arguments(fixture, tool.id()));
                    helper.assertTrue(reply.data().get("error").getAsString().equals("not_member"),
                            "membership rechecked for " + tool.id());
                });
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unloadedAndForeignTargetsAreRefusedWithoutLoading(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            JsonObject args = new JsonObject();
            args.add("at", fixture.at(new BlockPos(20_000_000, 64, 20_000_000)));
            Purity.assertPure(helper, "unloaded look_target", () -> {
                var reply = ReadTools.create().execute(fixture.player, "look_target", args);
                helper.assertTrue(reply.data().get("error").getAsString().equals("unloaded"), "unloaded target refused");
            });
            JsonObject foreign = new JsonObject();
            foreign.add("at", fixture.at(fixture.chest.above()));
            var reply = ReadTools.create().execute(fixture.player, "look_target", foreign);
            helper.assertTrue(reply.data().get("error").getAsString().equals("not_colony_target"),
                    "loaded foreign block refused");
            JsonObject invalid = new JsonObject();
            invalid.addProperty("resident_id", "invalid uuid");
            helper.assertTrue(ReadTools.create().execute(fixture.player, "resident_detail", invalid)
                    .data().get("error").getAsString().equals("invalid_arguments"), "bad UUID refused");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void schemasLimitsAndComponentTransport(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            JsonObject invalid = new JsonObject();
            invalid.addProperty("invented", true);
            helper.assertTrue(ReadTools.create().execute(fixture.player, "colony_overview", invalid)
                    .data().get("error").getAsString().equals("invalid_arguments"), "unknown args rejected");
            JsonObject large = new JsonObject();
            large.addProperty("large", "x".repeat(ToolReply.MAX_BYTES + 1));
            helper.assertTrue(new ToolReply(large, List.of()).capped(fixture.player.registryAccess())
                    .data().get("truncated").getAsBoolean(), "oversized results explicitly truncated");
            helper.assertTrue(new ToolReply(new JsonObject(), List.of(Component.literal(
                    "x".repeat(ToolReply.MAX_BYTES + 1)))).capped(fixture.player.registryAccess())
                    .data().get("truncated").getAsBoolean(), "Component bytes count toward the cap");
            ToolText text = new ToolText();
            JsonObject data = new JsonObject();
            data.add("message", text.text(Component.translatable("folkways.assistant.title")));
            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), fixture.player.registryAccess());
            try {
                text.reply(data).encode(buffer);
                ToolReply decoded = ToolReply.decode(buffer);
                helper.assertTrue(decoded.texts().getFirst().getContents() instanceof TranslatableContents,
                        "translation is still a Component after transport");
                helper.assertTrue(decoded.data().getAsJsonObject("message").has("$text"),
                        "server JSON contains a text reference");
            } finally {
                buffer.release();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void suspendedLaborIncludesPendingInputsWithoutReflectingIntoOptional(GameTestHelper helper) {
        UUID node = UUID.randomUUID();
        UUID resident = UUID.randomUUID();
        UUID producer = UUID.randomUUID();
        var goods = ItemSpec.of(Items.BREAD);
        var work = new HoldingRegistry.SuspendedWork(
            node, resident, 42, PersonContent.ID, PreemptReason.URGE);
        var input = new ReservedInput(node, Optional.empty(),
            Optional.empty(), goods, 2, Optional.of(producer));
        var claim = new Claim(
            new Resource.Handed(producer, goods), node, 2);
        var census = new LaborCensus(1, Map.of(), 0, Map.of(), Map.of(), Map.of());
        var snapshot = new LaborSnapshot(census, 0, 0, 0, Map.of(), List.of(),
            List.of(new LaborSnapshot.Suspended(work, List.of(input), List.of(claim))));
        JsonObject held = LaborReasons.snapshot(snapshot, helper.getLevel().getServer())
            .getAsJsonArray("suspended").get(0).getAsJsonObject();
        helper.assertValueEqual(held.getAsJsonObject("work").get("node").getAsString(), node.toString(),
            "suspended work retains its identity");
        JsonObject wanted = held.getAsJsonArray("inputs").get(0).getAsJsonObject();
        helper.assertTrue(wanted.get("pending").getAsBoolean()
            && wanted.get("producer").getAsString().equals(producer.toString()) && !wanted.has("resident"),
            "promised goods retain their producer without inventing a physical pack");
        helper.assertValueEqual(held.getAsJsonArray("claims").get(0).getAsJsonObject().getAsJsonObject("on")
            .get("type").getAsString(), "handed", "the held claim remains a production promise");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void laborCodesCarryTranslatedReasons(GameTestHelper helper) {
        Map<String, Integer> unassigned = new TreeMap<>();
        for (var reason : Unassigned.Reason.values()) {
            unassigned.put(reason.name(), 1);
        }
        var census = new LaborCensus(0, Map.of(), 0, unassigned, Map.of(), Map.of());
        var snapshot = new LaborSnapshot(census, 0, 0, 0, Map.of("NO_SOURCE", 3, "NO_ROOM", 1, "NO_WAY", 1,
                "NO_HAND", 1, "COOLING", 1, "TURNED:GOODS_GONE", 1), List.of(), List.of());
        JsonObject data = LaborReasons.snapshot(snapshot, helper.getLevel().getServer());
        ToolText text = new ToolText();
        LaborReasons.describe(data, text);
        ToolReply reply = text.reply(data);
        var shortages = data.getAsJsonArray("shortage_reasons");
        var waiting = data.getAsJsonObject("census").getAsJsonArray("unassigned_reasons");
        helper.assertTrue(shortages.size() == 6 && waiting.size() == Unassigned.Reason.values().length,
                "every Why and Unassigned code has a human-readable reason");
        for (var reasons : List.of(shortages, waiting)) {
            for (var reason : reasons) {
                var row = reason.getAsJsonObject();
                helper.assertTrue(row.has("code") && row.has("count") && row.getAsJsonObject("text").has("$text"),
                        "codes and counts retain client-resolved text alongside them");
                helper.assertTrue(reply.texts().get(row.getAsJsonObject("text").get("$text").getAsInt())
                        .getContents() instanceof TranslatableContents, "reason stays a Component on the server");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unknownResidentsStayListedAndKnownStockCanBeFiltered(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var person = PersonBody.RESIDENT.get().create(helper.getLevel());
            person.setPos(fixture.chest.above().getCenter());
            helper.getLevel().addFreshEntity(person);
            fixture.colony.hold(PersonContent.ID, Claimed.Entity.of(person)).orElseThrow();
            UUID absent = person.getUUID();
            person.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            JsonObject detail = new JsonObject();
            detail.addProperty("resident_id", absent.toString());
            Purity.assertPure(helper, "unloaded resident", () -> {
                var reply = ReadTools.create().execute(fixture.player, "resident_detail", detail);
                helper.assertTrue(reply.data().get("status").getAsString().equals("unloaded"),
                        "held unloaded resident is unknown, not absent");
                var listed = ReadTools.create().execute(fixture.player, "list_residents", new JsonObject());
                helper.assertTrue(listed.data().getAsJsonArray("residents").size() == 2,
                        "list retains loaded and unloaded residents");
            });
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(fixture.chest);
            chest.setLootTable(null);
            chest.setItem(0, new ItemStack(Items.BREAD, 5));
            JsonObject filter = new JsonObject();
            filter.addProperty("item", "minecraft:bread");
            Purity.assertPure(helper, "filtered known stock", () -> {
                var reply = ReadTools.create().execute(fixture.player, "stock", filter);
                var goods = reply.data().getAsJsonArray("goods");
                helper.assertTrue(goods.size() == 1 && goods.get(0).getAsJsonObject().get("count").getAsInt() == 5,
                        "known stock counted by item id");
                helper.assertFalse(reply.data().get("complete").getAsBoolean(), "unloaded pack stays unknown");
            });
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void stockRetainsComponentIdentitiesWhenFilteredByItem(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(fixture.chest);
            chest.setLootTable(null);
            chest.setItem(0, new ItemStack(Items.BREAD, 5));
            ItemStack named = new ItemStack(Items.BREAD, 2);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("Travel bread"));
            chest.setItem(1, named);
            JsonObject filter = new JsonObject();
            filter.addProperty("item", "minecraft:bread");
            Purity.assertPure(helper, "component-aware stock", () -> {
                var reply = ReadTools.create().execute(fixture.player, "stock", filter);
                var goods = reply.data().getAsJsonArray("goods");
                helper.assertValueEqual(goods.size(), 2, "component variants remain separate stock rows");
                var plain = goods.get(0).getAsJsonObject();
                var custom = goods.get(1).getAsJsonObject();
                helper.assertValueEqual(plain.get("count").getAsInt(), 5, "plain bread count");
                helper.assertValueEqual(custom.get("count").getAsInt(), 2, "named bread count");
                helper.assertFalse(plain.get("query").equals(custom.get("query")),
                    "stock queries retain distinct component identities");
                int name = custom.getAsJsonObject("name").get("$text").getAsInt();
                helper.assertValueEqual(reply.texts().get(name).getString(), "Travel bread",
                    "stock uses the component-aware display name");
            });
        }
        helper.succeed();
    }

    private static JsonObject arguments(ReadFixture fixture, String tool) {
        JsonObject args = new JsonObject();
        switch (tool) {
            case "reachable" -> args.addProperty("item", "minecraft:bread");
            case "plan_node" -> args.addProperty("id", UUID.randomUUID().toString());
            case "colony_block" -> args.add("at", fixture.at(fixture.chest));
            case "terrain", "rail_tracks" -> {
                args.add("at", fixture.at(fixture.chest));
                args.add("to", fixture.at(fixture.chest));
            }
            case "resident_detail" -> args.addProperty("resident_id", fixture.person.getUUID().toString());
            case "look_target" -> args.addProperty("entity_id", fixture.person.getId());
            default -> {
            }
        }
        return args;
    }
}
