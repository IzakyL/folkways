package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.Prefabs;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.runtime.LaborFixture;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Cleanup;
import io.github.izakyl.folkways.vanilla.fixture.facility.FacilityGateFixture;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
import io.github.izakyl.folkways.vanilla.person.ResidentEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class FacilityScriptGameTests {

    private static final String NAME = "north_gate";

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FacilityScriptGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 700)
    public static void scriptsDiscoverConfigureRunAndDeleteAGate(GameTestHelper helper) {
        var fixture = new FacilityGateFixture(helper);
        var player = new ServerPlayer(fixture.level.getServer(), fixture.level,
            new GameProfile(UUID.randomUUID(), "facility-test"), ClientInformation.createDefault());
        Charters.keep(fixture.colony, Charter.founded("Facility test", player.getUUID()));
        player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(fixture.colony.id()));
        ResidentEntity resident = (ResidentEntity) fixture.body.mob();
        fixture.colony.hold(PersonBody.ID, Claimed.Entity.of(resident)).orElseThrow();
        resident.joinColony(fixture.colony.id());
        fixture.colony.entered(resident);
        LaborFixture labor = new LaborFixture(fixture.colony, fixture.level);
        Cleanup.on(helper, () -> {
            labor.close();
            fixture.close();
        });
        helper.onEachTick(labor::tick);

        var facilities = DefinedFacilities.of(fixture.colony).orElseThrow();
        var host = new ScriptHost();
        BlockPos door = fixture.door.cell();
        BlockPos lever = fixture.lever.at().cell();
        BlockPos station = lever.north();
        fixture.level.setBlockAndUpdate(station, Blocks.CRAFTING_TABLE.defaultBlockState());
        SurveyFixture.confirm(helper, player, station, door);
        int morning = Math.floorMod(fixture.level.getDayTime() + 2000, 24000);
        int night = (morning + 1000) % 24000;
        var installed = host.run(player, """
            found = controls({"dimension": "%s", "from": %s, "to": %s})
            lever = [c for c in found["controls"] if c["part"] == "lever"][0]
            result = {"part": lever["part"], "reading": lever["reading"]}
            set("facilities/north_gate/definition", {
                "name": "north_gate", "extent": found["extent"],
                "anchor": [lever["x"], lever["y"], lever["z"]],
                "controls": {"lever": {"part": lever["part"], "offset": [0, 0, 0]}},
                "routines": {
                    "open": {"use": "folkways_core:write_all", "with": {"lever": True}},
                    "close": {"use": "folkways_core:write_all", "with": {"lever": False}},
                },
                "triggers": {
                    "open": {"kind": "manual", "routine": "open"},
                    "morning": {"kind": "schedule", "routine": "open", "dayTime": %d, "enabled": True},
                    "night": {"kind": "schedule", "routine": "close", "dayTime": %d, "enabled": True},
                },
                "projections": [{"gate": {"cells": [%s, %s], "axes": ["x"],
                                          "open_when": {"lever": True}}}],
                "ui": {"card": [{"button": "open"}, {"toggle": "night"},
                                {"readout": "lever", "as": "light"}]},
            })
            """.formatted(fixture.level.dimension().location(), xyz(station), xyz(door.above()), morning, night,
                coordinates(door), coordinates(door.above())), new JsonObject()).thenCompose(preview -> {
            var details = intent(helper, preview, 0).getAsJsonObject("details");
            helper.assertTrue(preview.data().getAsJsonObject("result").get("part").getAsString().equals("lever"),
                "controls found the lever");
            helper.assertTrue(details.get("facility").getAsString().equals(NAME)
                && details.getAsJsonArray("covered").size() == 1
                && details.getAsJsonArray("after_projections").size() == 1, "preview contains the gate and coverage");
            helper.assertTrue(facilities.read(NAME).isEmpty() && Prefabs.at(fixture.level, station).isPresent(),
                "creating a preview leaves the prefab in the world");
            return confirm(host, player, preview);
        }).thenCompose(applied -> {
            ok(helper, applied);
            helper.assertTrue(facilities.read(NAME).isPresent() && Prefabs.at(fixture.level, station).isEmpty(),
                "the approved definition covers the prefab");
            return host.run(player, """
                result = [ls("facilities"), glob("facilities/*/definition")]
                set("facilities/north_gate/runs/open", {"routine": "open"})
                """, new JsonObject());
        }).thenCompose(preview -> {
            var paths = preview.data().getAsJsonArray("result");
            helper.assertTrue(paths.get(0).toString().contains(NAME)
                && paths.get(1).toString().contains("facilities/" + NAME + "/definition"),
                "ls and glob discover an installed facility");
            helper.assertTrue(intent(helper, preview, 0).get("path").getAsString().equals("facilities/north_gate/runs/open") && !fixture.open(),
                "manual work waits for approval");
            return confirm(host, player, preview);
        }).thenAccept(applied -> ok(helper, applied));

        helper.startSequence().thenWaitUntil(() -> finished(helper, installed)).thenWaitUntil(() -> {
            var recent = facilities.read(NAME).orElseThrow().recent();
            helper.assertTrue(fixture.open() && recent.filter(run -> run.ending().equals("done")).isPresent(),
                "waiting for the resident to open the gate");
        }).thenWaitUntil(new Runnable() {
            private CompletableFuture<Void> changes;

            @Override
            public void run() {
                if (changes == null) {
                    changes = toggleAndDelete(helper, host, player, fixture, facilities, station);
                }
                finished(helper, changes);
            }
        }).thenSucceed();
    }

    private static CompletableFuture<Void> toggleAndDelete(GameTestHelper helper, ScriptHost host, ServerPlayer player,
            FacilityGateFixture fixture, DefinedFacilities facilities, BlockPos station) {
        var before = facilities.read(NAME).orElseThrow();
        return host.run(player, """
            set("facilities/north_gate/runs/open", {"routine": "open", "args": {"lever": True}})
            set("facilities/north_gate/triggers/night/enabled", False)
            set("facilities/north_gate/triggers/night/retries", 2)
            set("facilities/north_gate/triggers/night/backoff", 7)
            result = get("facilities/north_gate/triggers/night/enabled")
            """, new JsonObject()).thenCompose(preview -> {
            var state = intent(helper, preview, 0);
            var run = intent(helper, preview, 1);
            helper.assertTrue(state.get("path").getAsString().equals("facilities/north_gate/definition")
                && run.get("path").getAsString().equals("facilities/north_gate/runs/open") && !preview.data().get("result").getAsBoolean(),
                "the staged toggle is visible and precedes the run in the preview");
            return confirm(host, player, preview);
        }).thenCompose(applied -> {
            ok(helper, applied);
            var after = facilities.read(NAME).orElseThrow();
            var expected = new LinkedHashMap<>(before.triggers());
            expected.put("night", false);
            helper.assertTrue(after.triggers().equals(expected), "only the night trigger changed");
            var nightPolicy = ((Map<?, ?>) after.data().get("triggers")).get("night");
            helper.assertTrue(nightPolicy instanceof Map<?, ?> policy
                    && Integer.valueOf(2).equals(policy.get("retries"))
                    && Integer.valueOf(7).equals(policy.get("backoff")),
                "the state path writes the trigger's retry policy");
            return host.run(player, "set(\"facilities/north_gate/triggers/night/retries\", 11)", new JsonObject());
        }).thenCompose(invalid -> {
            helper.assertTrue(invalid.data().has("error") && !invalid.data().has("revision"),
                "the state path rejects an out-of-range retry count");
            return host.run(player, """
                gate = get("facilities/north_gate/definition")
                gate["ui"]["card"] = [{"readout": "missing", "as": "light"}]
                set("facilities/north_gate/definition", gate)
                set("facilities/north_gate/runs/open", {"routine": "open"})
                """, new JsonObject());
        }).thenCompose(preview -> {
            helper.assertTrue(preview.data().has("error") && !preview.data().has("revision")
                && preview.component("refusal").getString().contains("ui.card[0].readout"),
                "validation reports the bad readout and issues no confirmation token");
            return host.decide(player, "invalid-facility", true);
        }).thenCompose(rejected -> {
            helper.assertTrue(rejected.data().has("error") && !facilities.read(NAME).orElseThrow().triggers().get("night"),
                "the invalid batch cannot be confirmed");
            return host.run(player, """
                set("facilities/north_gate/routines/open", {
                    "source": "def step(state, readings, args): return 42, [done()]",
                    "with": {},
                })
                set("facilities/north_gate/runs/open", {"routine": "open"})
                """, new JsonObject());
        }).thenCompose(preview -> confirm(host, player, preview)).thenCompose(applied -> {
            ok(helper, applied);
            return host.run(player, """
                active = glob("facilities/north_gate/runs/*")
                result = get(active[0])
                delete("facilities/north_gate/runs/" + ls("facilities/north_gate/runs")[0])
                """, new JsonObject());
        }).thenCompose(preview -> {
            helper.assertTrue(intent(helper, preview, 0).get("after").isJsonNull()
                && preview.data().getAsJsonObject("result").get("routine").getAsString().equals("open")
                && !facilities.activeScripts(NAME).isEmpty(), "cancellation waits for approval");
            return confirm(host, player, preview);
        }).thenCompose(applied -> {
            ok(helper, applied);
            helper.assertTrue(facilities.activeScripts(NAME).isEmpty() && facilities.recent(NAME).orElseThrow()
                .reason().orElseThrow().equals("folkways.facility.script.cancelled_by_player"),
                "the approved cancellation finishes the edited script with its translated reason");
            return host.run(player, "delete(\"facilities/north_gate/definition\")", new JsonObject());
        }).thenCompose(preview -> {
            helper.assertTrue(intent(helper, preview, 0).getAsJsonObject("details").getAsJsonArray("restored").size()
                == 1, "deletion previews prefab restoration");
            return confirm(host, player, preview);
        }).thenAccept(applied -> {
            ok(helper, applied);
            helper.assertTrue(facilities.read(NAME).isEmpty()
                && Prefabs.at(fixture.level, station).isPresent(), "deletion restored the prefab");
        });
    }

    private static JsonObject intent(GameTestHelper helper, ToolReply reply, int index) {
        ok(helper, reply);
        return reply.data().getAsJsonArray("intents").get(index).getAsJsonObject();
    }

    private static CompletableFuture<ToolReply> confirm(ScriptHost host, ServerPlayer player, ToolReply preview) {
        return host.decide(player, preview.data().get("revision").getAsString(), true);
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), "unexpected script response: " + reply.localized());
    }

    private static void finished(GameTestHelper helper, CompletableFuture<Void> work) {
        helper.assertTrue(work.isDone(), "waiting for the script");
        AsyncWait.result(helper, work);
    }

    private static String xyz(BlockPos at) {
        return "{\"x\": %d, \"y\": %d, \"z\": %d}".formatted(at.getX(), at.getY(), at.getZ());
    }

    private static String coordinates(BlockPos at) {
        return "[%d, %d, %d]".formatted(at.getX(), at.getY(), at.getZ());
    }
}
