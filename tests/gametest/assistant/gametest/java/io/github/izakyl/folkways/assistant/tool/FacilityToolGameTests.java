package io.github.izakyl.folkways.assistant.tool;

import static io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture.FACILITY;

import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.vanilla.fixture.facility.ScriptedFacilityFixture;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class FacilityToolGameTests {

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void standardTreeFindChargesStateTraversal(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        var player = rig.keeper();
        var small = new ScriptHost().run(player, """
            load("@std//tree", "find")

            def selected(value):
                return value > 1

            result = find("client/scratch/*", where=selected, limit=1)
            """, new JsonObject(), """
            {"one": 1, "two": 2, "three": 3}
            """).thenCompose(reply -> {
                var rows = reply.reply().data().getAsJsonArray("result");
                helper.assertTrue(rows != null && rows.toString().equals(
                    "[{\"path\":\"client/scratch/three\",\"value\":3}]"),
                    "find filters fetched values and stops at the result limit: " + reply.reply().data());
                var scratch = new JsonObject();
                for (int index = 0; index < 10_000; index++) {
                    scratch.addProperty("entry_" + index, index);
                }
                return new ScriptHost(1000, 5000, 2000).run(player, """
                    load("@std//tree", "find")
                    result = find("client/scratch/*", limit=1)
                    """, new JsonObject(), scratch.toString());
            });
        AsyncWait.succeedWhen(helper, small, () -> {
            var data = small.join().reply().data();
            helper.assertTrue(data.has("error") && data.get("error").getAsString().equals("step_limit"),
                "a large glob exhausts the step budget even with limit=1: " + data);
        }, () -> { });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 2000)
    public static void approvedRoutineEditRunsNewSource(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("def step(state, readings, args): return state, [done]"));
        var host = new ScriptHost();
        var player = rig.keeper();
        var check = host.run(player, """
            set("facilities/Script test/routines/operate", {"source": '''
            def step(state, readings, args):
                if state == None: return True, [write("right", True)]
                return state, [done]
            '''})
            set("facilities/Script test/runs/operate", {"routine": "operate"})
            """, new JsonObject()).thenCompose(preview -> {
                helper.assertFalse(preview.data().has("error"), "routine edit and run preview: " + preview.data());
                helper.assertTrue(preview.data().getAsJsonArray("intents").size() == 2
                    && !rig.powered(rig.right) && rig.service.activeScripts(FACILITY).isEmpty(),
                    "edit and run both await approval");
                return approve(helper, host, player, preview);
            });
        helper.startSequence().thenWaitUntil(AsyncWait.until(helper, check::isDone, "routine edit approval"))
            .thenExecute(() -> AsyncWait.result(helper, check))
            .thenWaitUntil(AsyncWait.until(helper, () -> rig.recent("done"), "edited routine to finish"))
            .thenExecute(() -> helper.assertTrue(rig.powered(rig.right), "the new source wrote the lever"))
            .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 2000)
    public static void approvedCancelEndsRunAndReleasesItsControls(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, true);
        rig.install(rig.routine("""
            def step(state, readings, args):
                if state != None:
                    return state, [done]
                if args["value"]:
                    return True, [write("right", True), await("left", True, 24000)]
                return True, [write("right", False)]
            """));
        rig.run(Map.of("value", Value.of(true)));
        var host = new ScriptHost();
        var player = rig.keeper();
        var check = new CompletableFuture<ToolReply>();
        helper.startSequence()
            .thenWaitUntil(AsyncWait.until(helper, () -> rig.powered(rig.right), "first run to reach its await"))
            .thenExecute(() -> host.run(player, """
                runs = ls("facilities/Script test/runs")
                result = get("facilities/Script test/runs/" + runs[0])
                delete("facilities/Script test/runs/" + runs[0])
                """, new JsonObject()).thenCompose(preview -> {
                    helper.assertFalse(preview.data().has("error"), "cancel preview: " + preview.data());
                    helper.assertTrue(preview.data().getAsJsonObject("result").get("step").getAsInt() == 1
                        && preview.data().getAsJsonArray("intents").get(0).getAsJsonObject()
                            .get("after").isJsonNull()
                        && rig.service.activeScripts(FACILITY).size() == 1,
                        "the working run stays active until its cancellation is approved");
                    return approve(helper, host, player, preview);
                }).thenCompose(ignored -> host.run(player, """
                    result = [ls("facilities/Script test/runs"), get("facilities/Script test/recent")]
                    """, new JsonObject())).whenComplete((reply, failure) -> {
                        if (failure == null) {
                            check.complete(reply);
                        } else {
                            check.completeExceptionally(failure);
                        }
                    }))
            .thenWaitUntil(AsyncWait.until(helper, check::isDone, "cancellation approval and readback"))
            .thenExecute(() -> {
                var reply = AsyncWait.result(helper, check);
                helper.assertFalse(reply.data().has("error"), "cancel readback: " + reply.data());
                var result = reply.data().getAsJsonArray("result");
                helper.assertTrue(result.get(0).getAsJsonArray().isEmpty(), "the cancelled run disappeared");
                var recent = result.get(1).getAsJsonObject();
                int reason = recent.getAsJsonObject("reason").get("$text").getAsInt();
                helper.assertTrue(recent.get("ending").getAsString().equals("stalled")
                    && reply.texts().get(reason).getContents() instanceof TranslatableContents translated
                    && translated.getKey().equals("folkways.facility.script.cancelled_by_player"),
                    "recent carries the cancellation translation to the client");
                rig.run(Map.of("value", Value.of(false)));
            }).thenWaitUntil(AsyncWait.until(helper, () -> rig.recent("done"), "following run to finish"))
            .thenExecute(() -> helper.assertFalse(rig.powered(rig.right), "following run wrote the released control"))
            .thenSucceed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void facilityNamespaceListsDefinedRoutines(GameTestHelper helper) {
        var rig = new ScriptedFacilityFixture(helper, false);
        rig.install(rig.routine("def step(state, readings, args): return state, [done]"));
        var check = new ScriptHost().run(rig.keeper(), """
            root = "facilities/Script test"
            id = get(root)["id"]
            result = ["Script test" in ls("facilities"), ls("facilities/" + id + "/routines"),
                      glob("facilities/*/routines/*")]
            """, new JsonObject());
        helper.startSequence().thenWaitUntil(AsyncWait.until(helper, check::isDone, "facility namespace"))
            .thenExecute(() -> {
                var reply = AsyncWait.result(helper, check);
                helper.assertFalse(reply.data().has("error"), "facility namespace: " + reply.data());
                helper.assertTrue(reply.data().getAsJsonArray("result").toString()
                    .equals("[true,[\"operate\"],[\"facilities/Script test/routines/operate\"]]"),
                    "ls returns child names and glob returns full routine paths");
            }).thenSucceed();
    }

    private static CompletableFuture<ToolReply> approve(GameTestHelper helper, ScriptHost host, ServerPlayer player,
            ToolReply preview) {
        return host.decide(player, preview.data().get("revision").getAsString(), true).thenApply(reply -> {
            helper.assertFalse(reply.data().has("error"), "approval succeeded: " + reply.data());
            return reply;
        });
    }
}
