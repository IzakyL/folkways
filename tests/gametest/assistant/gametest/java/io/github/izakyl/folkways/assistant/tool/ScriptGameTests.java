package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ActionRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.assistant.tool.internal.ActionTools;
import io.github.izakyl.folkways.assistant.tool.internal.ReadTools;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ActionBatch;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.assistant.tool.internal.WorldReads;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityExtent;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysPlayerAttachments;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class ScriptGameTests {

    private ScriptGameTests() {
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptHostReportsMemoryRefusal(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        String script = """
            set("residents/%s/name", "Queued")
            result = [0] * 9000000
            """.formatted(fixture.person.getUUID());
        var check = host.run(fixture.player, script, new JsonObject()).thenAccept(reply -> {
            error(helper, reply, "memory_limit");
            helper.assertTrue(!reply.data().has("revision") && fixture.person.getCustomName() == null,
                    "the failed run discards its queued write");
            helper.assertTrue(reply.data().get("message").getAsString().contains("pre-allocation"),
                    "the refusal explains the exceeded allocation budget");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_script_reads", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptReadsArePureAndMatchTheOriginalHandlers(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ToolRegistry original = ReadTools.create();
        ToolRegistry observed = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        List<ToolReply> expected = new ArrayList<>();
        for (ToolSpec spec : original.specs()) {
            observed.register(spec, (context, args) -> {
                ToolReply[] read = {null};
                Purity.assertPure(helper, "script " + spec.id(), () -> {
                    fixture.warm();
                    read[0] = original.execute(context.player(), spec.id(), args);
                    expected.add(read[0]);
                });
                calls.incrementAndGet();
                return read[0];
            });
        }
        ScriptHost host = new ScriptHost(1_000_000, 10_000, 2000, observed);
        String script = "target = get(\"client/view\")[\"target\"]\n"
                + "result = [stock(), look(entity_id=target[\"entity_id\"]), player(), "
                + "reachable(\"minecraft:bread\"), recent_errors()]\n"
                + "name = get(\"colony\")[\"name\"]\n"
                + "print(name, {\"nested\": get(\"residents/" + fixture.person.getUUID() + "\")[\"name\"]})\n"
                + "print(name == \"Assistant fixture\")";
        JsonObject target = new JsonObject();
        target.addProperty("entity_id", fixture.person.getId());
        JsonObject view = new JsonObject();
        view.add("target", target);
        CompletableFuture<Void> check = host.run(fixture.player, script, view).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertTrue(calls.get() == 5, "all five query bindings ran");
            helper.assertTrue(reply.data().getAsJsonArray("result").size() == 5,
                    "list/dict conversion preserves data");
            for (int index = 0; index < 5; index++) {
                helper.assertTrue(reply.localized().getAsJsonArray("result").get(index).equals(expected.get(index)
                        .localized()), "script conversion preserves the original read output " + index);
            }
            helper.assertTrue(reply.texts().stream()
                    .anyMatch(text -> text.getContents() instanceof TranslatableContents),
                    "printed and returned text stays localizable");
            helper.assertTrue(reply.data().getAsJsonArray("prints").get(1).getAsJsonObject()
                    .getAsJsonArray("values").get(0).getAsBoolean() == false, "opaque text is not a server string");
            helper.assertTrue(reply.localized().toString().contains("Assistant fixture"),
                    "client resolves nested text");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void topLevelControlFlowReturnsResultsAndCapturesPrints(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player,
                "result = []\nfor i in range(3):\n    result.append(i)\n    print(i)", new JsonObject())
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("result").toString().equals("[0,1,2]"),
                            "loop assignments return through the module result");
                    helper.assertTrue(reply.data().getAsJsonArray("prints").size() == 3,
                            "each loop iteration prints once");
                    helper.assertTrue(reply.data().getAsJsonArray("prints").get(2).getAsJsonObject()
                            .getAsJsonArray("values").get(0).getAsInt() == 2, "printed loop value preserved");
                }).thenCompose(ignored -> host.run(fixture.player,
                        "if True:\n    result = 42\n    print(result)", new JsonObject()))
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().get("result").getAsInt() == 42, "conditional result returned");
                    helper.assertTrue(reply.data().getAsJsonArray("prints").get(0).getAsJsonObject()
                            .getAsJsonArray("values").get(0).getAsInt() == 42, "conditional print captured");
                }).thenCompose(ignored -> host.run(fixture.player,
                        "if False:\n    result = 42\nprint(\"done\")", new JsonObject()))
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().get("result").isJsonNull(), "unassigned result defaults to None");
                    helper.assertTrue(reply.data().getAsJsonArray("prints").size() == 1,
                            "prints survive without an assigned result");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void definitionsAndLoadsKeepTheOriginalControlFlowError(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player,
                "def f():\n    return 1\nfor i in range(1):\n    print(f())", new JsonObject())
                .thenAccept(reply -> {
                    error(helper, reply, "syntax_error");
                    helper.assertTrue(reply.data().get("line").getAsInt() == 3
                            && reply.data().get("column").getAsInt() == 1, "def prevents wrapping at original line");
                    helper.assertTrue(reply.data().get("message").getAsString().startsWith("for loops"),
                            "original restriction returned instead of a nested function error");
                }).thenCompose(ignored -> host.run(fixture.player,
                        "load(\"missing\", \"value\")\nfor i in range(1):\n    print(i)", new JsonObject()))
                .thenAccept(reply -> {
                    error(helper, reply, "syntax_error");
                    helper.assertTrue(reply.data().get("line").getAsInt() == 2
                            && reply.data().get("column").getAsInt() == 1, "load prevents wrapping at original line");
                    helper.assertTrue(reply.data().get("message").getAsString().startsWith("for loops"),
                            "load is never retried inside a function");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void namespaceListsResidentsAndHasNoNewPaths(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var check = host.run(fixture.player, """
            result = [ls("residents"), glob("residents/*"), glob("residents/*/name")]
            """, new JsonObject()).thenCompose(reply -> {
                ok(helper, reply);
                var result = reply.data().getAsJsonArray("result");
                String id = fixture.person.getUUID().toString();
                helper.assertTrue(result.get(0).getAsJsonArray().contains(new com.google.gson.JsonPrimitive(id)),
                        "ls returns the resident segment");
                helper.assertTrue(result.get(1).toString().contains("residents/" + id)
                        && result.get(2).toString().contains("residents/" + id + "/name"),
                        "glob returns full paths");
                return expect(helper, fixture, host, "set(\"facilities/new/example\", {})",
                        "unknown_state_path", true);
            });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void createdDrawingsHaveTemporaryAndApprovedPaths(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var queries = ActionGameTests.queries(fixture);
        String script = """
            zone = %s
            path = %s
            zone["kind"] = "folkways_vanilla:ground"
            zone["parameters"] = {"forage": True}
            z = "facilities/marked zone"
            p = "facilities/marked path"
            set(z, zone)
            set(p, path)
            result = [z, p, get(z) == zone and z.split("/")[1] in ls("facilities"),
                      get(p) == path and p.split("/")[1] in ls("facilities")]
            """.formatted(queries.get("create_zone"), queries.get("create_path"));
        var check = host.run(fixture.player, script, new JsonObject()).thenCompose(preview -> {
            ok(helper, preview);
            var seen = preview.data().getAsJsonArray("result");
            String zone = seen.get(0).getAsString();
            String path = seen.get(1).getAsString();
            helper.assertTrue(zone.startsWith("facilities/") && seen.get(2).getAsBoolean(),
                    "the temporary zone is readable and listed in the script");
            helper.assertTrue(path.startsWith("facilities/") && seen.get(3).getAsBoolean(),
                    "the temporary path is readable and listed in the script");
            helper.assertTrue(preview.data().getAsJsonArray("intents").size() == 2,
                    "the two drawings and initial parameters share one confirmation");
            return host.decide(fixture.player, preview.data().get("revision").getAsString(), true)
                    .thenCompose(applied -> {
                        ok(helper, applied);
                        var drawings = applied.data().getAsJsonArray("applied");
                        String zoneId = drawings.get(0).getAsJsonObject().get("holding_id").getAsString();
                        String pathId = drawings.get(1).getAsJsonObject().get("holding_id").getAsString();
                        helper.assertTrue(!zone.equals("facilities/" + zoneId) && !path.equals("facilities/" + pathId),
                                "approval assigns real IDs");
                        return host.run(fixture.player, """
                            result = [get("facilities/%s"), get("facilities/%s"), "%s" in ls("facilities"), "%s" in ls("facilities"), get("facilities/%s/parameters/forage")]
                            """.formatted(zoneId, pathId, zoneId, pathId, zoneId), new JsonObject());
                    });
        }).thenAccept(reply -> {
            ok(helper, reply);
            var seen = reply.data().getAsJsonArray("result");
            helper.assertTrue(seen.get(0).getAsJsonObject().getAsJsonArray("cells").size() == 1
                    && seen.get(2).getAsBoolean() && seen.get(4).getAsBoolean(), "the approved zone is readable and listed by its real ID");
            helper.assertTrue(seen.get(1).getAsJsonObject().getAsJsonArray("points").size() == 2
                    && seen.get(3).getAsBoolean(), "the approved path is readable and listed by its real ID");
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void residentAndZoneCanBeDecidedIndependently(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var queries = ActionGameTests.queries(fixture);
        var work = queries.get("set_work");
        String script = """
            set("residents/%s/work/%s", {"keenness": 4})
            set("facilities/new zone", %s)
            """.formatted(fixture.person.getUUID(), work.get("vocation").getAsString(), queries.get("create_zone"));
        var check = host.run(fixture.player, script, new JsonObject()).thenCompose(preview -> {
            ok(helper, preview);
            var units = ConfirmedAction.reviewUnits(preview);
            helper.assertTrue(units.size() == 2, "the resident and the zone each have a review unit");
            helper.assertTrue(ActionBatch.autoApprove(units.getFirst(), Set.of("work"))
                    && !ActionBatch.autoApprove(units.getLast(), Set.of("work")), "auto-approval is per category");
            return host.decide(fixture.player, revision(units.getLast()), true).thenCompose(zone -> {
                ok(helper, zone);
                helper.assertTrue(zone.data().getAsJsonArray("applied").size() == 1, "only the zone was applied");
                return host.decide(fixture.player, revision(units.getFirst()), true);
            });
        }).thenCompose(resident -> {
            ok(helper, resident);
            helper.assertTrue(resident.data().getAsJsonArray("applied").size() == 1,
                    "the zone approval did not stale the work change");
            return host.run(fixture.player, script.replace("4}", "2}"), new JsonObject());
        }).thenCompose(preview -> {
            ok(helper, preview);
            var units = ConfirmedAction.reviewUnits(preview);
            return host.decide(fixture.player, revision(units.getFirst()), false).thenCompose(resident -> {
                helper.assertTrue(resident.data().has("declined"), "the resident was rejected");
                return host.decide(fixture.player, revision(units.getLast()), true);
            });
        }).thenAccept(zone -> ok(helper, zone));
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void facilityUsingNewZoneSharesItsReviewUnit(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        JsonObject zone = ActionGameTests.queries(fixture).get("create_zone");
        var from = zone.getAsJsonObject("from");
        var cell = new BlockPos(from.get("x").getAsInt(), from.get("y").getAsInt(), from.get("z").getAsInt());
        var extent = FacilityExtent.blocks(List.of(WorldPos.of(helper.getLevel(), cell)));
        var definition = new Gson().toJson(Map.of("name", "yard", "extent", DefinitionCodec.extent(extent),
                "anchor", List.of(cell.getX(), cell.getY(), cell.getZ())));
        var check = host.run(fixture.player, """
            set("facilities/yard/definition", %s)
            set("facilities/yard zone", %s)
            """.formatted(definition, zone), new JsonObject()).thenCompose(preview -> {
                ok(helper, preview);
                var units = ConfirmedAction.reviewUnits(preview);
                helper.assertTrue(units.size() == 1 && units.getFirst().data().getAsJsonArray("intents").size() == 2,
                        "the facility and its zone share a review unit");
                helper.assertFalse(ActionBatch.autoApprove(units.getFirst(), Set.of("settings", "drawings")),
                        "a facility definition requires explicit approval");
                return host.decide(fixture.player, revision(units.getFirst()), true);
            }).thenAccept(applied -> {
                ok(helper, applied);
                var changes = applied.data().getAsJsonArray("applied");
                helper.assertTrue(changes.size() == 2 && changes.get(0).getAsJsonObject().has("holding_id"),
                        "approval creates the zone before its facility");
            });
        finish(helper, fixture, check);
    }

    private static String revision(ToolReply preview) {
        return preview.data().get("revision").getAsString();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void wrappedErrorsUseOriginalLinesAndColumns(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = host.run(fixture.player,
                "total = 1\nfor i in range(1):\n    print(i)\n    result = total // 0", new JsonObject())
                .thenAccept(reply -> {
                    error(helper, reply, "eval_error");
                    helper.assertTrue(reply.data().get("line").getAsInt() == 4
                            && reply.data().get("column").getAsInt() == 20, "eval position maps to original operator");
                }).thenCompose(ignored -> host.run(fixture.player,
                        "if True:\n    result = missing", new JsonObject()))
                .thenAccept(reply -> {
                    error(helper, reply, "syntax_error");
                    helper.assertTrue(reply.data().get("line").getAsInt() == 2
                            && reply.data().get("column").getAsInt() == 14, "resolver position maps to original name");
                }).thenCompose(ignored -> host.run(fixture.player,
                        "if True:\n    result = \"\"\"first\nsecond\"\"\"[99]", new JsonObject()))
                .thenAccept(reply -> {
                    error(helper, reply, "eval_error");
                    helper.assertTrue(reply.data().get("line").getAsInt() == 3
                            && reply.data().get("column").getAsInt() == 10,
                            "string continuation columns are not shifted");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void wrappingPreservesMultilineStringContents(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        String script = "for i in range(1):\n    text = \"\"\"first\n  second\n\nlast\"\"\"\n"
                + "    raw = r'''def fake():\nload(\"ignored\")\n  \\n'''\n    print(text)\n    result = [text, raw]";
        finish(helper, fixture, new ScriptHost().run(fixture.player, script, new JsonObject()).thenAccept(reply -> {
            ok(helper, reply);
            helper.assertTrue(reply.data().getAsJsonArray("result").get(0).getAsString()
                    .equals("first\n  second\n\nlast"), "triple quoted contents and blank lines preserved");
            helper.assertTrue(reply.data().getAsJsonArray("result").get(1).getAsString()
                    .equals("def fake():\nload(\"ignored\")\n  \\n"), "raw string contents preserved");
            helper.assertTrue(reply.data().getAsJsonArray("prints").get(0).getAsJsonObject()
                    .getAsJsonArray("values").get(0).getAsString().equals("first\n  second\n\nlast"),
                    "printed multiline contents preserved");
        }));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void wrappedScriptsKeepBudgetsAndQueueIntentsOnce(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        String id = fixture.person.getUUID().toString();
        ToolRegistry reads = ReadTools.create();
        ToolRegistry observed = new ToolRegistry();
        AtomicInteger calls = new AtomicInteger();
        ToolSpec player = reads.specs().stream().filter(spec -> spec.id().equals("player_context"))
                .findFirst().orElseThrow();
        observed.register(player, (context, args) -> {
            calls.incrementAndGet();
            return reads.execute(context.player(), player.id(), args);
        });
        String script = "set(\"residents/" + id + "/name\", \"First\")\nprint(\"before\")\nfor name in [\"Second\"]:\n"
                + "    set(\"residents/" + id + "/name\", name)\n    print(name)";
        CompletableFuture<Void> check = new ScriptHost().run(fixture.player, script, new JsonObject())
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("intents").size() == 1,
                            "repeated writes produce one path diff across the retry");
                    helper.assertTrue(reply.data().getAsJsonArray("prints").size() == 2,
                            "each print is captured once across the retry");
                    helper.assertTrue(fixture.person.getCustomName() == null, "wrapped scripts only preview intents");
                }).thenCompose(ignored -> expect(helper, fixture, new ScriptHost(1_000_000, 10_000, 2, observed),
                        "p = player()\nfor i in range(2):\n    player()", "call_limit", true))
                .thenRun(() -> helper.assertTrue(calls.get() == 2, "the failed resolution used no binding calls"))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(50, 10_000, 2000),
                        "for i in range(10000):\n    result = i", "step_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(1_000_000_000, 20, 2000),
                        "for i in range(100000000):\n    x = i * i", "timeout", false))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "for i in range(33):\n    set(\"residents/" + id + "/name\", \"Ada\")", "too_many_intents", true))
                .thenRun(() -> helper.assertTrue(fixture.person.getCustomName() == null,
                        "wrapped errors discard queued intents"));
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptErrorsHavePositionsAndBoundedExecution(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        CompletableFuture<Void> check = expect(helper, fixture, new ScriptHost(), "x".repeat(8193),
                "script_size_cap", false)
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "result = (", "syntax_error", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "result = 1 // 0", "eval_error", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(50, 10_000, 2000),
                        "result = [i for i in range(10000)]", "step_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(1_000_000_000, 20, 2000),
                        "def spin():\n    for i in range(100000000):\n        x = i * i\nspin()", "timeout", false))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(1_000_000, 10_000, 2),
                        "result = [player() for i in range(3)]", "call_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "result = \"x\" * 50000", "result_size_cap", false))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "result = set(\"residents/invalid/name\", \"Ada\")", "invalid_arguments", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "result = [set(\"residents/" + fixture.person.getUUID() + "/name\", \"Ada\") for i in range(33)]",
                        "too_many_intents", true))
                .thenCompose(ignored -> expect(helper, fixture, new ScriptHost(),
                        "set(\"residents/" + fixture.person.getUUID() + "/name\", \"Ada\")\nresult = 1 // 0", "eval_error", true))
                .thenRun(() -> helper.assertTrue(fixture.person.getCustomName() == null,
                        "errors discard every queued intent"));
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void membershipForeignAndUnloadedChecksSurviveTheScriptBoundary(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = expect(helper, fixture, host,
                "result = look(at=" + fixture.at(fixture.chest.above()) + ")", "not_colony_target", true)
                .thenCompose(ignored -> expect(helper, fixture, host, "result = look(at="
                        + fixture.at(new BlockPos(20_000_000, 64, 20_000_000)) + ")", "unloaded", true))
                .thenCompose(ignored -> {
                    Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
                    return expect(helper, fixture, host, "result = ls(\"residents\")", "not_member", true);
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void aSingleDecisionAppliesFinalStateAndRejectDiscardsIt(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        String id = fixture.person.getUUID().toString();
        String script = """
                path = "residents/%s/name"
                set(path, "First")
                set(path, "Second")
                set("client/scratch/name", get(path))
                result = [get("client/scratch/name"), get("residents/%s")["name"]]
                """.formatted(id, id);
        CompletableFuture<Void> check = host.run(fixture.player, script, new JsonObject(), "{}").thenCompose(run -> {
            ToolReply preview = run.reply();
            ok(helper, preview);
            helper.assertTrue("{\"name\":\"Second\"}".equals(run.scratch()),
                    "scratch is returned before state confirmation");
            helper.assertTrue(fixture.person.getCustomName() == null, "script and preview never renamed");
            helper.assertTrue(preview.data().getAsJsonArray("intents").size() == 1, "one preview lists the final path diff");
            helper.assertTrue(preview.data().getAsJsonArray("intents").get(0).getAsJsonObject()
                    .get("path").getAsString().equals("residents/" + id + "/name"), "preview identifies the path");
            var seen = preview.localized().getAsJsonArray("result");
            helper.assertTrue(seen.get(0).getAsString().equals("Second"), "state reads see staged values");
            helper.assertTrue(seen.get(1).getAsString().equals("Second"), "resident details include the staged name");
            String revision = preview.data().get("revision").getAsString();
            return host.decide(fixture.player, revision, true).thenAccept(applied -> {
                ok(helper, applied);
                helper.assertTrue(applied.data().getAsJsonArray("applied").size() == 1, "one path applied");
                helper.assertTrue(fixture.person.getCustomName().equals(Component.literal("Second")), "ordered rename");
                error(helper, host.decide(fixture.player, revision, true).getNow(null), "stale");
            });
        }).thenCompose(ignored -> host.run(fixture.player, "set(\"residents/" + id + "/name\", \"Rejected\")", new JsonObject()))
                .thenCompose(preview -> host.decide(fixture.player,
                        preview.data().get("revision").getAsString(), false))
                .thenAccept(reply -> {
                    helper.assertTrue(reply.data().get("message").getAsString().equals("player rejected"),
                            "rejection reaches the model");
                    helper.assertTrue(fixture.person.getCustomName().equals(Component.literal("Second")),
                            "reject changed nothing");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void batchTokensRejectTamperingStalenessRevocationAndUnloading(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        String script = "set(\"residents/" + fixture.person.getUUID() + "/name\", \"Ada\")";
        CompletableFuture<Void> check = host.run(fixture.player, script, new JsonObject()).thenCompose(preview -> {
            String revision = preview.data().get("revision").getAsString();
            error(helper, host.decide(fixture.player, revision + "x", true).getNow(null), "stale");
            fixture.person.setCustomName(Component.literal("Changed elsewhere"));
            return host.decide(fixture.player, revision, true).thenAccept(reply -> error(helper, reply, "stale"));
        }).thenCompose(ignored -> host.run(fixture.player, script, new JsonObject())).thenCompose(preview -> {
            Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
            return host.decide(fixture.player, preview.data().get("revision").getAsString(), true)
                    .thenAccept(reply -> error(helper, reply, "not_member"));
        }).thenCompose(ignored -> {
            Charters.keep(fixture.colony, Charter.founded("Assistant fixture", fixture.player.getUUID()));
            return host.run(fixture.player, script, new JsonObject());
        }).thenCompose(preview -> {
            fixture.person.setRemoved(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            return host.decide(fixture.player, preview.data().get("revision").getAsString(), true)
                    .thenAccept(reply -> error(helper, reply, "unloaded"));
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void mixedBatchesRespectEveryAutoApprovalCategory(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ActionBatch batches = new ActionBatch(ActionTools.create());
            Map<String, JsonObject> queries = ActionGameTests.queries(fixture);
            ToolReply preview = batches.preview(fixture.player, List.of(
                    new ActionBatch.Intent("rename_resident", queries.get("rename_resident")),
                    new ActionBatch.Intent("set_setting", queries.get("set_setting"))),
                    new ToolReply(new JsonObject(), List.of()));
            ok(helper, preview);
            helper.assertFalse(ActionBatch.autoApprove(preview, Set.of("residents")),
                    "partial approval cannot apply batch");
            helper.assertTrue(ActionBatch.autoApprove(preview, Set.of("residents", "settings")),
                    "every category approved");
            for (String action : List.of("erase_zone_or_path", "commission_build")) {
                preview = batches.preview(fixture.player, List.of(new ActionBatch.Intent(action, queries.get(action))),
                        new ToolReply(new JsonObject(), List.of()));
                ok(helper, preview);
                helper.assertFalse(ActionBatch.autoApprove(preview,
                        Set.of("residents", "settings", "work", "drawings", "building")),
                        action + " always needs explicit confirmation");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void stagedDestructivePathsRequireManualApproval(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var queries = ActionGameTests.queries(fixture);
        String writes = """
            set("residents/%s/name", "After")
            set("facilities/folkways_vanilla:orders/parameters/count", 7)
            """.formatted(fixture.person.getUUID());
        var build = queries.get("commission_build");
        build.add("dimension", build.getAsJsonObject("at").remove("dimension"));
        var survey = queries.get("request_survey");
        String zone = "facilities/" + queries.get("erase_zone_or_path").get("id").getAsString();
        Set<String> categories = Set.of("residents", "settings", "drawings", "building", "surveys");
        var check = host.run(fixture.player, writes, new JsonObject()).thenAccept(preview -> {
            ok(helper, preview);
            helper.assertFalse(ActionBatch.autoApprove(preview, Set.of("residents")), "every category is required");
            helper.assertFalse(ActionBatch.autoApprove(preview, categories),
                    "a facility parameter requires explicit approval");
        }).thenCompose(ignored -> host.run(fixture.player, "set(\"scripts/test\", \"def f(): return 1\")",
                new JsonObject())).thenAccept(preview -> {
            ok(helper, preview);
            helper.assertFalse(ActionBatch.autoApprove(preview, categories), "a script write requires explicit approval");
        });
        Map<String, String> destructive = Map.of(
                "builds/proposed", "set(\"builds/proposed\", " + build + ")",
                "surveys/proposed", "set(\"surveys/proposed\", " + survey + ")",
                "delete", "delete(\"" + zone + "\")");
        for (var entry : destructive.entrySet()) {
            check = check.thenCompose(ignored -> host.run(fixture.player, writes + entry.getValue(), new JsonObject()))
                    .thenAccept(preview -> {
                        ok(helper, preview);
                        var intents = preview.data().getAsJsonArray("intents");
                        helper.assertTrue(intents.size() == 3, "preview includes both writes and the destructive intent");
                        var intent = intents.get(2).getAsJsonObject();
                        if (entry.getKey().equals("delete")) {
                            helper.assertTrue(intent.get("path").getAsString().equals(zone)
                                    && intent.get("after").isJsonNull(), "preview shows the zone deletion");
                        } else {
                            helper.assertTrue(intent.get("path").getAsString().equals(entry.getKey())
                                    && intent.has("summary") && intent.get("before").isJsonNull(),
                                    "preview identifies " + entry.getKey() + " as a new path");
                        }
                        helper.assertTrue(intent.get("destructive").getAsBoolean()
                                && categories.contains(intent.get("category").getAsString()),
                                "the additional intent is destructive in an allowed category");
                        helper.assertFalse(ActionBatch.autoApprove(preview, categories),
                                entry.getKey() + " forces manual approval of the mixed batch");
                    });
        }
        check = check.thenCompose(ignored -> host.run(fixture.player, writes, new JsonObject()))
                .thenCompose(preview -> {
                    ok(helper, preview);
                    var units = ConfirmedAction.reviewUnits(preview);
                    return host.decide(fixture.player, revision(units.getFirst()), true).thenCompose(resident -> {
                        ok(helper, resident);
                        return host.decide(fixture.player, revision(units.getLast()), true);
                    });
                }).thenCompose(applied -> {
                    ok(helper, applied);
                    return host.run(fixture.player, """
                        scope = "facilities/folkways_vanilla:orders"
                        id = get(scope)["id"]
                        result = [get(scope + "/parameters/count"), get("facilities/" + id + "/parameters/count")]
                        """, new JsonObject());
                }).thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("result").toString().equals("[7,7]"),
                            "the parameter write is readable by scope name and UUID");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void clientMarksAreReadOnlyQuestionData(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        JsonObject view = new JsonObject();
        var check = host.run(fixture.player, "result = get('client/marks') == None", view).thenCompose(empty -> {
            ok(helper, empty);
            helper.assertTrue(empty.data().get("result").getAsBoolean(), "missing marks read as None");
            JsonObject marks = new JsonObject();
            marks.addProperty("tool", "kind:folkways_vanilla:ground");
            marks.addProperty("dimension", fixture.player.serverLevel().dimension().location().toString());
            marks.addProperty("tick", 42);
            var corners = new com.google.gson.JsonArray();
            corners.add(fixture.at(fixture.chest));
            marks.add("corners", corners);
            view.add("marks", marks);
            return host.run(fixture.player, """
                    result = [get("client/marks"), "marks" in ls("client"), "marks" in get("client/view")]
                    """, view);
        }).thenCompose(reply -> {
            ok(helper, reply);
            var result = reply.data().getAsJsonArray("result");
            helper.assertTrue(result.get(0).equals(view.get("marks")) && result.get(1).getAsBoolean()
                    && !result.get(2).getAsBoolean(), "book marks are exposed at their own path");
            return expect(helper, fixture, host, "delete('client/marks')", "state_read_only", false);
        });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void batchApplyFailuresReportTheAppliedPrefix(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ActionRegistry actions = new ActionRegistry();
            AtomicInteger applied = new AtomicInteger();
            ToolSpec spec = stepSpec();
            actions.register(new ActionRegistry.Spec(spec, "residents", false), (context, args) ->
                    new ActionRegistry.Plan(Component.literal("Test"), args, "state", () -> {
                        if (args.get("step").getAsString().equals("broken")) {
                            throw new IllegalStateException("Test apply failure");
                        }
                        applied.incrementAndGet();
                        JsonObject data = new JsonObject();
                        data.addProperty("changed", true);
                        return CompletableFuture.completedFuture(new ToolReply(data, List.of()));
                }));
            ActionBatch batches = new ActionBatch(actions);
            ToolReply preview = batches.preview(fixture.player, List.of(step("first"), step("broken")),
                    new ToolReply(new JsonObject(), List.of()));
            helper.assertTrue(applied.get() == 0, "preview does not apply either intent");
            ToolReply result = batches.decide(fixture.player, preview.data().get("revision").getAsString(), true)
                    .getNow(null);
            error(helper, result, "action_failed");
            helper.assertTrue(result.data().get("failed_intent").getAsInt() == 1
                    && result.data().getAsJsonArray("applied").size() == 1 && applied.get() == 1,
                    "a failed batch reports the applied prefix without claiming rollback");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void asynchronousBatchesCannotFollowThePlayerIntoAnotherColony(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            Colony other = Charters.found(fixture.player, "Other colony");
            try {
                ActionRegistry actions = new ActionRegistry();
                CompletableFuture<ToolReply> gate = new CompletableFuture<>();
                AtomicInteger later = new AtomicInteger();
                ToolSpec spec = stepSpec();
                actions.register(new ActionRegistry.Spec(spec, "residents", false), (context, args) ->
                        new ActionRegistry.Plan(Component.literal("Test"), args, "state", () -> {
                            if (args.get("step").getAsString().equals("wait")) {
                                return gate;
                            }
                            later.incrementAndGet();
                            return CompletableFuture.completedFuture(new ToolReply(new JsonObject(), List.of()));
                    }));
                ActionBatch batches = new ActionBatch(actions);
                ToolReply preview = batches.preview(fixture.player, List.of(step("wait"), step("later")),
                        new ToolReply(new JsonObject(), List.of()));
                CompletableFuture<ToolReply> result = batches.decide(fixture.player,
                        preview.data().get("revision").getAsString(), true);
                helper.assertFalse(result.isDone(), "the first apply is asynchronous");
                fixture.player.setData(FolkwaysPlayerAttachments.CURRENT_COLONY, Optional.of(other.id()));
                gate.complete(new ToolReply(new JsonObject(), List.of()));
                error(helper, result.getNow(null), "stale");
                helper.assertTrue(later.get() == 0, "later intents cannot target the newly selected colony");
            } finally {
                other.raze();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void batchRejectsPlanChangedBeforeItsTurn(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper)) {
            ActionRegistry actions = new ActionRegistry();
            CompletableFuture<ToolReply> gate = new CompletableFuture<>();
            AtomicInteger world = new AtomicInteger();
            AtomicInteger applied = new AtomicInteger();
            actions.register(new ActionRegistry.Spec(stepSpec(), "residents", false), (context, args) ->
                    new ActionRegistry.Plan(Component.literal("Test"), args, Integer.toString(world.get()), () -> {
                        if (args.get("step").getAsString().equals("wait")) {
                            return gate;
                        }
                        applied.incrementAndGet();
                        return CompletableFuture.completedFuture(new ToolReply(new JsonObject(), List.of()));
                    }));
            ActionBatch batches = new ActionBatch(actions);
            ToolReply preview = batches.preview(fixture.player, List.of(step("wait"), step("later")),
                    new ToolReply(new JsonObject(), List.of()));
            CompletableFuture<ToolReply> pending = batches.decide(fixture.player, revision(preview), true);
            helper.assertFalse(pending.isDone(), "the approved batch is waiting for its first operation");
            world.incrementAndGet();
            gate.complete(new ToolReply(new JsonObject(), List.of()));
            ToolReply result = pending.getNow(null);
            error(helper, result, "stale");
            helper.assertTrue(applied.get() == 0 && result.data().getAsJsonArray("applied").size() == 1,
                    "the changed plan was refused after the first operation");
        }
        helper.succeed();
    }

    private static ToolSpec stepSpec() {
        JsonObject fields = new JsonObject();
        JsonObject step = new JsonObject();
        step.addProperty("type", "string");
        fields.add("step", step);
        return ToolRegistry.spec("test", "Test", fields, "step");
    }

    private static ActionBatch.Intent step(String step) {
        JsonObject args = new JsonObject();
        args.addProperty("step", step);
        return new ActionBatch.Intent("test", args);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void worldIsPrivilegedReadOnlyLoadedAndBounded(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper, 2);
        ScriptHost host = new ScriptHost();
        helper.assertFalse(ScriptApi.help(null, false).contains("world."), "ordinary help hides world");
        helper.assertFalse(ScriptApi.run(false).description().contains("world."), "ordinary run reference hides world");
        helper.assertTrue(ScriptApi.help(null, true).contains("world.block"), "privileged help advertises world");
        Charters.keep(fixture.colony, Charter.founded("Other", UUID.randomUUID()));
        CompletableFuture<Void> check = host.run(fixture.player, "result = [world.block(" + fixture.at(fixture.chest)
                + "), world.entities(" + fixture.at(fixture.chest) + ", {\"x\":" + fixture.chest.getX()
                + ",\"y\":" + (fixture.chest.getY() + 4) + ",\"z\":" + fixture.chest.getZ()
                + "}, limit=1), world.registry(\"items\", limit=2), world.players()]", new JsonObject())
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonArray("result").get(0).getAsJsonObject()
                            .get("id").getAsString().equals("minecraft:chest"), "raw non-colony block read");
                    helper.assertTrue(reply.data().getAsJsonArray("result").get(2).getAsJsonObject()
                            .getAsJsonArray("ids").size() == 2, "registry limit enforced");
                }).thenCompose(ignored -> expect(helper, fixture, host, "world.block(" + fixture.at(
                        new BlockPos(20_000_000, 64, 20_000_000)) + ")", "unloaded", true))
                .thenCompose(ignored -> expect(helper, fixture, host, "world.registry(\"items\", limit=129)",
                        "bounds_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, host,
                        "world.entities(" + fixture.at(fixture.chest) + ", "
                                + fixture.at(fixture.chest.offset(65, 0, 0)) + ")", "bounds_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, host, "world.players(limit=129)", "bounds_limit", true))
                .thenCompose(ignored -> expect(helper, fixture, host,
                        "world.registry(\"items\", limit=4294967297)", "invalid_arguments", true))
                .thenCompose(ignored -> expect(helper, fixture, host,
                        "world.block({\"dimension\":\"minecraft:overworld\",\"x\":4294967297,\"y\":64,\"z\":0})",
                        "invalid_arguments", true))
                .thenCompose(ignored -> expect(helper, fixture, host, "world.block = 3", "eval_error", true));
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void rawWorldAccessorsArePureAndNeverOpenLoot(GameTestHelper helper) {
        try (ReadFixture fixture = new ReadFixture(helper, 2)) {
            JsonObject at = new JsonObject();
            at.add("at", fixture.at(fixture.chest));
            JsonObject box = at.deepCopy();
            box.add("to", fixture.at(fixture.chest.above(4)));
            JsonObject registry = new JsonObject();
            registry.addProperty("kind", "items");
            for (Map.Entry<String, JsonObject> read : Map.of("block", at, "entities", box,
                    "registry", registry, "players", new JsonObject()).entrySet()) {
                Purity.assertPure(helper, "world." + read.getKey(), () ->
                        ok(helper, WorldReads.read(fixture.player, read.getKey(), read.getValue())));
            }
            ChestBlockEntity chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(fixture.chest);
            helper.assertTrue(chest.getLootTable() != null,
                    "raw NBT never generates loot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void outputReferencesCannotBeForgedAndCopiesCannotMutateTheWorld(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper, 2);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = expect(helper, fixture, host, "result = {\"$text\":999}",
                "invalid_result", false).thenCompose(ignored -> host.run(fixture.player,
                        "b = world.block(" + fixture.at(fixture.chest) + ")\nb[\"id\"] = \"modified\"\n"
                                + "result = world.block(" + fixture.at(fixture.chest) + ")", new JsonObject()))
                .thenAccept(reply -> {
                    ok(helper, reply);
                    helper.assertTrue(reply.data().getAsJsonObject("result").get("id").getAsString()
                            .equals("minecraft:chest"), "raw values are detached copies");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void worldPermissionIsRecheckedAfterTheScriptStarts(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper, 2);
        ToolRegistry reads = ReadTools.create();
        ToolRegistry observed = new ToolRegistry();
        ToolSpec player = reads.specs().stream().filter(spec -> spec.id().equals("player_context"))
                .findFirst().orElseThrow();
        observed.register(player, (context, args) -> {
            ToolReply reply = reads.execute(context.player(), player.id(), args);
            fixture.permission = 0;
            return reply;
        });
        ScriptHost host = new ScriptHost(1_000_000, 10_000, 2000, observed);
        finish(helper, fixture, expect(helper, fixture, host,
                "p = player()\nresult = world.players()", "permission_denied", true));
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void ordinaryPlayersCannotNameWorldAndRunsShareTheNetworkBudget(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        CompletableFuture<Void> check = expect(helper, fixture, host, "result = world.players()", "syntax_error", true)
                .thenCompose(ignored -> {
                    CompletableFuture<ToolReply> first = host.run(fixture.player,
                            "result = [player() for i in range(10)]", new JsonObject());
                    error(helper, host.run(fixture.player, "result = 1", new JsonObject()).getNow(null),
                            "already_running");
                    for (int index = 0; index < 21; index++) {
                        host.run(fixture.player, "result = 1", new JsonObject());
                    }
                    error(helper, host.run(fixture.player, "result = 1", new JsonObject()).getNow(null),
                            "rate_limited");
                    return first.thenAccept(reply -> ok(helper, reply));
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void clientScratchPersistsWithoutApproval(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        JsonObject view = new JsonObject();
        var check = host.run(fixture.player, """
                set("client/scratch", {"samples": [None, True, 2, 3.0, "fish", (4, 5), {"n": 6}]})
                set("client/scratch/a%2Fb", True)
                result = len(get("client/scratch/samples"))
                """, view, "{}").thenCompose(first -> {
                    ok(helper, first.reply());
                    helper.assertTrue(first.reply().data().get("result").getAsInt() == 7
                            && first.scratch() != null && !first.reply().data().has("revision"),
                            "scratch is written without confirmation");
                    return host.run(fixture.player, "result = len(get(\"client/scratch\"))", view, "{}")
                            .thenCompose(other -> {
                                ok(helper, other.reply());
                                helper.assertTrue(other.reply().data().get("result").getAsInt() == 0,
                                        "a second session from the same player starts empty");
                                return host.run(fixture.player, """
                                        keys = ls("client/scratch")
                                        paths = glob("client/scratch/*")
                                        delete("client/scratch/a%2Fb")
                                        samples = get("client/scratch/samples")
                                        samples[6]["n"] += 1
                                        samples.append(8)
                                        set("client/scratch/samples", samples)
                                        result = [samples[6]["n"], len(samples),
                                                  type(samples[3]), type(samples[5]), keys, paths]
                                        """, view, first.scratch());
                            });
                }).thenAccept(second -> {
                    ok(helper, second.reply());
                    var result = second.reply().data().getAsJsonArray("result");
                    helper.assertTrue(result.get(0).getAsInt() == 7 && result.get(1).getAsInt() == 8
                            && result.get(2).getAsString().equals("float")
                            && result.get(3).getAsString().equals("list")
                            && result.get(4).getAsJsonArray().get(0).getAsString().equals("a%2Fb")
                            && result.get(5).getAsJsonArray().get(0).getAsString().equals("client/scratch/a%2Fb")
                            && !second.scratch().contains("a/b") && !second.reply().data().has("revision"),
                            "the next run can list, read, write and delete scratch");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scratchRejectsInvalidWritesAndKeepsEarlierWrites(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        JsonObject view = new JsonObject();
        var check = host.run(fixture.player, """
                set("client/scratch/n", 7)
                set("client/scratch/n", "界" * 90000)
                """, view, "{}").thenCompose(oversized -> {
                    error(helper, oversized.reply(), "scratch_size_cap");
                    helper.assertTrue("{\"n\":7}".equals(oversized.scratch()), "earlier successful write persists");
                    return host.run(fixture.player, """
                            set("client/scratch/n", print)
                            """, view, oversized.scratch());
                }).thenCompose(invalid -> {
                    error(helper, invalid.reply(), "eval_error");
                    helper.assertTrue(invalid.scratch() == null, "scratch rejects functions");
                    return host.run(fixture.player, """
                            set("client/scratch/n", 99)
                            set("residents/%s/name", "Ada")
                            result = 1 // 0
                            """.formatted(fixture.person.getUUID()), view, "{\"n\":7}");
                }).thenAccept(failed -> {
                    error(helper, failed.reply(), "eval_error");
                    helper.assertTrue("{\"n\":99}".equals(failed.scratch()) && !failed.reply().data().has("revision")
                            && fixture.person.getCustomName() == null,
                            "a later script error preserves scratch and discards the staged colony change");
                });
        finish(helper, fixture, check);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void capturesAreQueuedOnlyForSuccessfulImageEnabledScripts(GameTestHelper helper) {
        ReadFixture fixture = new ReadFixture(helper);
        ScriptHost host = new ScriptHost();
        var enabled = new ScriptHost.Client(new JsonObject(), "{}", true, "zh_cn",
                path -> CompletableFuture.failedFuture(new AssertionError("unexpected history read")));
        var check = host.run(fixture.player, "capture()", new JsonObject(), "{}").thenCompose(disabled -> {
            error(helper, disabled.reply(), "image_input_disabled");
            helper.assertTrue(disabled.captures().isEmpty(), "disabled image input never queues a capture");
            return host.run(fixture.player, "capture()\nresult = 1 // 0", enabled);
        }).thenCompose(failed -> {
            error(helper, failed.reply(), "eval_error");
            helper.assertTrue(failed.captures().isEmpty(), "failed scripts drop queued captures");
            return host.run(fixture.player, "capture(around=[[0, 60, 0], [4, 62, 4]], views=5)\ncapture()\n"
                    + "capture()", enabled);
        }).thenCompose(limited -> {
            error(helper, limited.reply(), "capture_limit");
            helper.assertTrue(limited.captures().isEmpty(), "more than six images fails the run");
            return host.run(fixture.player, "capture(pitch=91)", enabled);
        }).thenCompose(invalid -> {
            error(helper, invalid.reply(), "invalid_arguments");
            return host.run(fixture.player, "capture(around=\"changes\", views=[\"north\", \"north\"])", enabled);
        }).thenCompose(repeated -> {
            error(helper, repeated.reply(), "invalid_arguments");
            return host.run(fixture.player, "capture(around=\"changes\", position=[0, 60, 0])", enabled);
        }).thenCompose(mixed -> {
            error(helper, mixed.reply(), "invalid_arguments");
            return host.run(fixture.player, "capture(around=\"changes\", views=6)", enabled);
        }).thenCompose(counted -> {
            error(helper, counted.reply(), "invalid_arguments");
            return host.run(fixture.player, "capture(position=[0, 61, 0], look_at=[2, 60, 2], views=2)", enabled);
        }).thenCompose(unframed -> {
            message(helper, unframed.reply(), "views needs around");
            return host.run(fixture.player, "capture(camera=\"free\")", enabled);
        }).thenCompose(named -> {
            message(helper, named.reply(), "camera: expected object");
            return host.run(fixture.player, "capture(position={\"dimension\": \"minecraft:overworld\", \"x\": 0, "
                    + "\"y\": 61, \"z\": 0}, look_at={\"x\": 2, \"y\": 60, \"z\": 2})", enabled);
        }).thenCompose(dicts -> {
            ok(helper, dicts.reply());
            helper.assertTrue(dicts.captures().size() == 1, "position and look_at may be XYZ dicts");
            return host.run(fixture.player, """
                    capture(around={"from": {"x": 0, "y": 60, "z": 0}, "to": [4, 62, 4]}, views=["north", "top"])
                    capture(around="changes", views=2)
                    capture()
                    capture(camera={"yaw": 90}, fov=60)
                    print(help("guide:pastures"))
                    """, enabled);
        }).thenAccept(success -> {
            ok(helper, success.reply());
            helper.assertTrue(success.captures().size() == 4
                    && success.captures().getFirst().getAsJsonArray("views").size() == 2
                    && success.captures().getLast().get("yaw").getAsInt() == 90
                    && !success.reply().data().has("revision"), "successful capture requests need no approval");
            helper.assertTrue(success.reply().data().toString().contains("成年牲畜"),
                    "the help binding uses the client language");
        });
        finish(helper, fixture, check);
    }

    private static CompletableFuture<Void> expect(GameTestHelper helper, ReadFixture fixture, ScriptHost host,
            String source, String code, boolean position) {
        return host.run(fixture.player, source, new JsonObject()).thenAccept(reply -> {
            error(helper, reply, code);
            if (position) {
                helper.assertTrue(reply.data().get("line").getAsInt() > 0
                        && reply.data().get("column").getAsInt() > 0,
                        "source line and column present: " + reply.data());
            }
        });
    }

    // An invalid call names what was wrong, so the model can fix it rather than guess.
    private static void message(GameTestHelper helper, ToolReply reply, String expected) {
        helper.assertTrue(reply.data().has("error") && reply.data().has("message") && reply.data().get("message").getAsString().contains(expected),
                "the error says \"" + expected + "\": " + reply.data());
    }

    private static void ok(GameTestHelper helper, ToolReply reply) {
        helper.assertFalse(reply.data().has("error"), "script succeeded: " + reply.data());
    }

    private static void error(GameTestHelper helper, ToolReply reply, String code) {
        helper.assertTrue(reply != null && reply.data().has("error")
                && reply.data().get("error").getAsString().equals(code), "expected " + code + ": " + reply);
    }

    private static void finish(GameTestHelper helper, ReadFixture fixture, CompletableFuture<?> check) {
        AsyncWait.succeedWhen(helper, check, () -> { }, fixture::close);
    }
}
