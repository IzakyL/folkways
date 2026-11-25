package io.github.izakyl.folkways.assistant.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.izakyl.folkways.assistant.tool.api.ToolRegistry;
import io.github.izakyl.folkways.assistant.tool.api.ToolReply;
import io.github.izakyl.folkways.assistant.tool.api.ToolSpec;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptApi;
import io.github.izakyl.folkways.assistant.tool.internal.ScriptHost;
import io.github.izakyl.folkways.core.colony.charter.Charter;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.internal.Asks;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.Known;
import io.github.izakyl.folkways.core.plan.internal.Message;
import io.github.izakyl.folkways.core.plan.internal.PlanInputs;
import io.github.izakyl.folkways.core.plan.internal.Planner;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Shown;
import io.github.izakyl.folkways.core.plan.internal.Solving;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.plan.internal.Unassigned;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.plan.internal.WeaveDelta;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.runtime.ColonyLabor;
import io.github.izakyl.folkways.core.runtime.Labor;
import io.github.izakyl.folkways.core.runtime.LaborPlan;
import io.github.izakyl.folkways.core.testing.AsyncWait;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("folkways_assistant")
@PrefixGameTestTemplate(false)
public final class PlanGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "plan_read");

    private record Work(NodeSpec spec) implements Node {

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void realPlanDependenciesReasonsAndReadsArePure(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper)) {
            var scene = new PlanningFixture(helper);
            var solver = new Solving();
            var plannerField = Solving.class.getDeclaredField("planner");
            plannerField.setAccessible(true);
            Planner planner = (Planner) plannerField.get(solver);
            try {
                Work missing = work(scene, true);
                Work first = work(scene, false);
                Work last = work(scene, false);
                var goal = new Grown(List.of(first, last, missing), List.of(new Before(first.id(), last.id())),
                        List.of());
                var stock = new Stock(List.of(), Map.of(new Stash(scene.target), 27));
                var known = new Known(scene.situation(stock, List.of()), scene.crew.hands());
                var planned = solver.handle(known, List.of(new Message.Submitted(OWNER, goal)), 41, true);
                long solves = planner.rounds();
                fixture.woven(List.of(new Message.Submitted(OWNER, goal)), planned.woven());
                var laid = ColonyLabor.class.getDeclaredMethod("laid",
                        io.github.izakyl.folkways.core.plan.internal.Scheduler.Scheduled.class);
                laid.setAccessible(true);
                laid.invoke(fixture.labor, planned.laid());
                helper.assertTrue(planner.rounds() == solves, "view maintenance does not solve again");
                Set<UUID> actual = new HashSet<>();
                fixture.plan().tasks().stream().filter(task -> fixture.plan().onGraph(task.id()))
                        .forEach(task -> actual.add(task.id()));
                helper.assertTrue(actual.equals(planned.weave().vertices().keySet()),
                        "independent reader has the Weaver's exact node set");
                fixture.read("plan_nodes", new JsonObject());
                JsonArray nodes = fixture.read("plan_nodes", new JsonObject()).getAsJsonArray("nodes");
                helper.assertTrue(nodes.size() == 3, "script sees every planner node");
                JsonObject detail = fixture.read("plan_node", id(first.id()));
                var node = detail.getAsJsonObject("node");
                helper.assertTrue(node.getAsJsonArray("after").toString().contains(last.id().toString()),
                        "direct downstream dependency retained");
                helper.assertTrue(fixture.read("plan_node", id(last.id())).getAsJsonObject("node")
                        .getAsJsonArray("before").toString().contains(first.id().toString()),
                        "direct upstream dependency retained");
                var trace = fixture.read("trace", new JsonObject()).getAsJsonArray("trace");
                helper.assertTrue(find(trace, "node", missing.id()).get("outcome").getAsString().equals("refused"),
                        "missing material is refused");
                helper.assertTrue(find(trace, "node", missing.id()).get("code").getAsString().equals("no_source"),
                        "missing material uses shared shortage code");
                helper.assertTrue(find(trace, "node", first.id()).get("outcome").getAsString().equals("assigned"),
                        "feasible work is assigned");
                helper.assertTrue(fixture.read("plan_node", id(missing.id())).getAsJsonObject("node")
                        .getAsJsonArray("reasons").toString().contains("NO_SOURCE"), "shortfall details retained");
                JsonObject filters = new JsonObject();
                filters.addProperty("vocation", Vocations.HAULING.toString());
                filters.addProperty("doing", first.spec().doing().toString());
                filters.add("site", fixture.reads.at(scene.target.cell()));
                helper.assertTrue(fixture.read("plan_nodes", filters).getAsJsonArray("nodes").size() == 3,
                        "vocation, doing and exact site filters agree");
                filters.add("site", fixture.reads.at(scene.target.cell().above()));
                helper.assertTrue(fixture.read("plan_nodes", filters).getAsJsonArray("nodes").isEmpty(),
                        "other sites cannot expose unrelated tasks");
                filters = new JsonObject();
                filters.addProperty("resident_id", scene.crew.hands().getFirst().id().toString());
                helper.assertFalse(fixture.read("plan_nodes", filters).getAsJsonArray("nodes").isEmpty(),
                        "assigned resident filter matches the schedule");
                fixture.read("plan_inputs", new JsonObject());
                fixture.read("plan_events", new JsonObject());
                helper.assertTrue(planner.rounds() == solves, "all reads leave solve count unchanged");
                helper.assertTrue(detail.get("capture_tick").getAsLong() == 41, "capture is planning tick");
            } finally {
                solver.closed();
                scene.worker.body().discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void cooldownsEventsStructuralReasonsAndMembership(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper)) {
            var scene = new PlanningFixture(helper);
            try {
                Work task = work(scene, false);
                var delta = Shown.delta(Map.of(task.id(), new Vertex(task.id(), task, Placement.NOWHERE)),
                        List.of(), List.of());
                var cooldowns = new Cooldowns();
                PlanningFixture.cooldown(cooldowns, task.id(),
                    Goods.specOf(Items.STONE.getDefaultInstance()), 90, 2);
                var woven = new Planner.Woven(delta, new Crew(List.of()), scene.ways, false, List.of(), List.of(),
                        List.of(), Asks.NONE, new Planner.Timing(0, 0), 1, cooldowns.snapshot(), PlanInputs.EMPTY,
                        90, List.of(), true, Map.of(), Map.of());
                fixture.woven(List.of(new Message.Started(task.id(), fixture.reads.person.getUUID()),
                        new Message.Failed(task.id(), LaborRefusal.WORK_BROKE)), woven);
                var cooling = fixture.read("plan_node", id(task.id())).getAsJsonObject("node")
                        .getAsJsonObject("cooldown");
                helper.assertTrue(cooling.get("failures").getAsInt() == 2
                        && cooling.get("retry_at").getAsLong() == 110, "failure count and retry tick captured");
                helper.assertTrue(fixture.read("trace", new JsonObject()).getAsJsonArray("trace").get(0)
                        .getAsJsonObject().get("outcome").getAsString().equals("cooling"), "trace reports cooldown");
                var events = fixture.read("plan_events", new JsonObject()).getAsJsonArray("events");
                helper.assertTrue(events.toString().contains("Started") && events.toString().contains("Failed"),
                        "started and failed messages retained");
                JsonObject filtered = new JsonObject();
                filtered.addProperty("node", UUID.randomUUID().toString());
                helper.assertTrue(fixture.read("plan_events", filtered).getAsJsonArray("events").isEmpty(),
                        "events filter does not leak other nodes");
                for (int i = 0; i < 520; i++) {
                    PlanningFixture.apply(fixture.labor, helper.getLevel(), List.of(new Message.Finished(task.id())),
                            WeaveDelta.NONE);
                }
                helper.assertTrue(fixture.plan().events().size() == 512, "colony ring is bounded");
                JsonObject invalidTick = new JsonObject();
                invalidTick.addProperty("since_tick", -1);
                error(helper, fixture, "plan_events", invalidTick, "invalid_tick");
                var diagnosed = LaborPlan.class.getDeclaredMethod("diagnosed",
                        io.github.izakyl.folkways.core.plan.internal.Diagnosis.class);
                diagnosed.setAccessible(true);
                diagnosed.invoke(fixture.plan(), new io.github.izakyl.folkways.core.plan.internal.Diagnosis(List.of(),
                        List.of(new Unassigned(task.id(), Unassigned.Reason.NO_TOOL, task.id())), List.of()));
                var stalled = fixture.read("trace", new JsonObject()).getAsJsonArray("trace").get(0).getAsJsonObject();
                helper.assertTrue(stalled.get("structural").getAsBoolean()
                        && stalled.get("outcome").getAsString().equals("stalled"), "structural blockage is explicit");
                helper.assertTrue(fixture.read("plan_node", id(task.id())).getAsJsonObject("node")
                        .getAsJsonArray("reasons").toString().contains("root"), "blockedBy/root retained");
                Charters.keep(fixture.reads.colony, Charter.founded("Other", UUID.randomUUID()));
                for (String name : List.of("plan_nodes", "plan_node", "plan_inputs", "plan_events", "trace")) {
                    JsonObject args = name.equals("plan_node") ? id(task.id()) : new JsonObject();
                    Purity.assertPure(helper, name + " rechecks membership", () -> helper.assertTrue(
                            ToolRegistry.READS.execute(fixture.reads.player, name, args).data().get("error")
                                    .getAsString().equals("not_member"), "non-member refused"));
                }
            } finally {
                scene.worker.body().discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void largeGraphPagesTrimAndRejectStaleOrForeignQueries(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper)) {
            var scene = new PlanningFixture(helper);
            try {
                Map<UUID, Vertex> graph = new LinkedHashMap<>();
                List<Before> links = new ArrayList<>();
                Work head = work(scene, false);
                graph.put(head.id(), new Vertex(head.id(), head, Placement.NOWHERE));
                for (int i = 0; i < 700; i++) {
                    Work next = work(scene, false);
                    graph.put(next.id(), new Vertex(next.id(), next, Placement.NOWHERE));
                    links.add(new Before(head.id(), next.id()));
                }
                PlanningFixture.applyWhole(fixture.labor, helper.getLevel(), List.of(), graph, links, List.of(),
                        Set.of(), List.of(), Set.of());
                JsonObject args = new JsonObject();
                args.addProperty("limit", 17);
                Set<String> seen = new HashSet<>();
                JsonObject first = fixture.read("plan_nodes", args);
                JsonObject page = first;
                do {
                    for (var element : page.getAsJsonArray("nodes")) {
                        helper.assertTrue(seen.add(element.getAsJsonObject().get("id").getAsString()),
                                "pages never repeat a node");
                    }
                    if (page.get("next_cursor").isJsonNull()) {
                        break;
                    }
                    args.add("cursor", page.get("next_cursor"));
                    page = fixture.read("plan_nodes", args);
                } while (true);
                helper.assertTrue(seen.size() == graph.size(), "large graph pages include all nodes");
                var detail = fixture.read("plan_node", id(head.id()));
                helper.assertTrue(detail.get("truncated").getAsBoolean(), "large dependencies proactively trimmed");
                args.add("cursor", first.get("next_cursor"));
                PlanningFixture.apply(fixture.labor, helper.getLevel(), List.of(), WeaveDelta.NONE);
                error(helper, fixture, "plan_nodes", args, "stale_cursor");
                args.addProperty("cursor", "!");
                error(helper, fixture, "plan_nodes", args, "invalid_cursor");
                args.remove("cursor");
                args.addProperty("limit", 65);
                error(helper, fixture, "plan_nodes", args, "invalid_limit");
                args.addProperty("limit", 4_294_967_297L);
                error(helper, fixture, "plan_nodes", args, "invalid_limit");
                error(helper, fixture, "plan_node", id(UUID.randomUUID()), "unknown_node");
                args = new JsonObject();
                args.addProperty("resident_id", UUID.randomUUID().toString());
                helper.assertTrue(fixture.read("plan_nodes", args).getAsJsonArray("nodes").isEmpty(),
                        "foreign resident cannot expose nodes");
                helper.assertTrue(ScriptApi.help("trace", false).contains("structural")
                        && ScriptApi.run(false).description().contains("plan_node(id)"),
                        "help and run reference updated");
            } finally {
                scene.worker.body().discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void planningInputsStayFrozenAndNeverProbeWorkshops(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper)) {
            var scene = new PlanningFixture(helper);
            var solver = new Solving();
            try {
                var stone = Goods.specOf(Items.STONE.getDefaultInstance());
                var diamond = Goods.specOf(Items.DIAMOND.getDefaultInstance());
                var maker = new Work(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(scene.target),
                        Stances.at(scene.target), Workload.Once.of(1))
                        .needs(new Need(stone, 4)).gives(new Amount(diamond, 1)).done());
                AtomicInteger probes = new AtomicInteger();
                Workshop workshop = new Workshop() {
                    @Override
                    public ResourceLocation id() {
                        return OWNER;
                    }

                    @Override
                    public UUID key() {
                        return maker.id();
                    }

                    @Override
                    public WorkSite site() {
                        return maker.spec().site();
                    }

                    @Override
                    public List<Refinement.Change> options(Intent intent, Grown graph) {
                        probes.incrementAndGet();
                        return intent instanceof Produce produce && produce.wanted().admits(diamond)
                                ? List.of(Refinement.Change.of(Grown.of(maker))) : List.of();
                    }
                };
                ItemStack stack = new ItemStack(Items.STONE, 12);
                var stock = new Stock(List.of(new Stock.Holding(new Stash(scene.source), stack),
                        new Stock.Holding(new Stash(scene.target), new ItemStack(Items.BREAD, 3))),
                        Map.of(new Stash(scene.source), 20, new Stash(scene.target), 20));
                var planned = solver.handle(new Known(scene.situation(stock, List.of(workshop)), scene.crew.hands()),
                        List.of(new Message.Submitted(OWNER, Grown.of(work(scene, true)))), 55, true);
                PlanningFixture.apply(fixture.labor, helper.getLevel(), planned);
                int before = probes.get();
                stack.setCount(1);
                JsonObject args = new JsonObject();
                args.addProperty("kind", "stock");
                var rows = fixture.read("plan_inputs", args).getAsJsonArray("inputs");
                helper.assertTrue(rows.asList().stream().map(row -> row.getAsJsonObject())
                        .anyMatch(row -> row.get("item").getAsString().equals("minecraft:stone")
                                && row.get("count").getAsLong() == 12 && row.get("locations").getAsInt() == 1),
                        "captured stock is immutable, counted by item and location");
                args.addProperty("limit", 1);
                var page = fixture.read("plan_inputs", args);
                helper.assertTrue(page.get("truncated").getAsBoolean(), "planning inputs paginate");
                args.add("cursor", page.get("next_cursor"));
                helper.assertTrue(fixture.read("plan_inputs", args).get("next_cursor").isJsonNull(),
                        "planning input cursor reaches the end");
                args.remove("cursor");
                args.addProperty("kind", "workshops");
                var shop = fixture.read("plan_inputs", args).getAsJsonArray("inputs").get(0).getAsJsonObject();
                helper.assertTrue(shop.getAsJsonArray("processes").toString().contains("minecraft:diamond")
                        && shop.getAsJsonArray("processes").toString().contains("minecraft:stone"),
                        "observed processing inputs and outputs are visible");
                helper.assertFalse(shop.get("processes_complete").getAsBoolean(), "catalogue is explicitly partial");
                helper.assertTrue(probes.get() == before, "reads never probe workshops or grow extra work");
                args.addProperty("kind", "invented");
                error(helper, fixture, "plan_inputs", args, "invalid_arguments");
            } finally {
                solver.closed();
                scene.worker.body().discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", batch = "assistant_plan_script", timeoutTicks = AsyncWait.TIMEOUT_TICKS)
    public static void scriptBindingsReadCapturedPlans(GameTestHelper helper) throws Exception {
        Fixture fixture = new Fixture(helper);
        var scene = new PlanningFixture(helper);
        Work task = work(scene, false);
        PlanningFixture.applyWhole(fixture.labor, helper.getLevel(), List.of(),
                Map.of(task.id(), new Vertex(task.id(), task, Placement.NOWHERE)));
        scene.worker.body().discard();
        ToolRegistry observed = new ToolRegistry();
        for (ToolSpec spec : ToolRegistry.READS.specs()) {
            observed.register(spec, (context, args) -> {
                ToolReply[] reply = {null};
                Purity.assertPure(helper, "script " + spec.id(), () ->
                        reply[0] = ToolRegistry.READS.execute(context.player(), spec.id(), args));
                return reply[0];
            });
        }
        ScriptHost host = new ScriptHost(1_000_000, 10_000, 2000, observed);
        var check = host.run(fixture.reads.player, "result = [plan_nodes(), plan_node(\"" + task.id()
                + "\"), plan_inputs(), plan_events(), trace()]", new JsonObject());
        AsyncWait.succeedWhen(helper, check, () -> {
            ToolReply reply = check.join();
            helper.assertFalse(reply.data().has("error"), "all planner bindings run: " + reply.data());
            helper.assertTrue(reply.data().getAsJsonArray("result").size() == 5, "all five results returned");
        }, () -> {
            try {
                fixture.close();
            } catch (Exception broken) {
                throw new AssertionError(broken);
            }
        });
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unwantedPlanningBuildsNoTaskOrProcessSummaries(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper, false)) {
            var scene = new PlanningFixture(helper);
            var planner = new Planner(new Claims(), new Cooldowns());
            try {
                var diamond = Goods.specOf(Items.DIAMOND.getDefaultInstance());
                var stone = Goods.specOf(Items.STONE.getDefaultInstance());
                Work maker = new Work(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(scene.target),
                        Stances.at(scene.target), Workload.Once.of(1))
                        .needs(new Need(stone, 4)).gives(new Amount(diamond, 1)).done());
                AtomicInteger probes = new AtomicInteger();
                Workshop workshop = new Workshop() {
                    @Override
                    public ResourceLocation id() {
                        return OWNER;
                    }

                    @Override
                    public UUID key() {
                        return maker.id();
                    }

                    @Override
                    public WorkSite site() {
                        return maker.spec().site();
                    }

                    @Override
                    public List<Refinement.Change> options(Intent intent, Grown graph) {
                        probes.incrementAndGet();
                        return intent instanceof Produce produce && produce.wanted().admits(diamond)
                                ? List.of(Refinement.Change.of(Grown.of(maker))) : List.of();
                    }
                };
                Work missing = new Work(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(scene.target),
                        Stances.at(scene.target), Workload.Once.of(1))
                        .needs(new Need(Goods.specOf(Items.EMERALD.getDefaultInstance()), 1)).done());
                var batch = List.<Message>of(new Message.Submitted(OWNER, Grown.of(work(scene, true))),
                        new Message.Submitted(OWNER, Grown.of(missing)));
                long now = helper.getLevel().getGameTime();
                var stock = new Stock(List.of(), Map.of(new Stash(scene.source), 27, new Stash(scene.target), 27));
                var cold = planner.handle(new Known(scene.situation(stock, List.of(workshop)), scene.crew.hands()),
                        batch, now, Map.of(), false);
                helper.assertTrue(probes.get() > 0 && !cold.shortfalls().isEmpty(),
                        "cold round exercises workshop probing and failed node growth");
                helper.assertTrue(summaryCounts(planner).equals(List.of(0L, 0L)),
                        "unwanted Costs/Weaver construct zero Process/PlanTask summaries");
                helper.assertTrue(cold.inputs() == PlanInputs.EMPTY && cold.cooldowns().isEmpty()
                        && cold.attempted().isEmpty() && !cold.wanted(), "unwanted round exports no summaries");
                fixture.woven(batch, cold);
                helper.assertFalse(fixture.plan().wanted(now), "structural tracking does not request summaries");
                var warming = fixture.read("plan_nodes", new JsonObject());
                helper.assertTrue(warming.get("warming").getAsBoolean(), "first read reports pending summaries");
                helper.assertTrue(fixture.plan().wanted(now + 5999) && !fixture.plan().wanted(now + 6000),
                        "demand lasts exactly 6000 ticks");
                helper.assertTrue(summaryCounts(planner).equals(List.of(0L, 0L)),
                        "reading nodes formats on the server without invoking Costs/Weaver");
                var supplied = new Stock(List.of(new Stock.Holding(new Stash(scene.source),
                        new ItemStack(Items.STONE, 16))), Map.of(new Stash(scene.source), 27,
                        new Stash(scene.target), 27));
                var warm = planner.handle(new Known(scene.situation(supplied, List.of(workshop)), scene.crew.hands()),
                        List.of(Message.ANSWERED), now, Map.of(), fixture.plan().wanted(now));
                fixture.woven(List.of(Message.ANSWERED), warm);
                helper.assertTrue(summaryCounts(planner).stream().allMatch(count -> count > 0),
                        "wanted round constructs task and process summaries as a positive control");
                var ready = fixture.read("plan_inputs", new JsonObject());
                helper.assertFalse(ready.get("warming").getAsBoolean(), "next wanted round publishes summaries");
                helper.assertTrue(ready.getAsJsonArray("inputs").toString().contains("minecraft:stone"),
                        "captured inputs are readable after demand");
                long solves = planner.rounds();
                fixture.read("trace", new JsonObject());
                helper.assertTrue(planner.rounds() == solves, "warming reads never solve");
                var counts = summaryCounts(planner);
                var expired = planner.handle(new Known(scene.situation(supplied, List.of(workshop)),
                        scene.crew.hands()), List.of(Message.ANSWERED), now + 6000, Map.of(),
                        fixture.plan().wanted(now + 6000));
                fixture.woven(List.of(Message.ANSWERED), expired);
                helper.assertTrue(summaryCounts(planner).equals(counts), "expired demand stops summary construction");
                helper.assertTrue(expired.inputs() == PlanInputs.EMPTY && expired.attempted().isEmpty(),
                        "expired round exports no snapshots");
                helper.assertTrue(fixture.read("plan_inputs", new JsonObject()).get("warming").getAsBoolean(),
                        "reactivation waits for a fresh snapshot");
            } finally {
                planner.closed();
                scene.worker.body().discard();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void pipelineHonorsDemandAndEventsDeferFormatting(GameTestHelper helper) throws Exception {
        try (Fixture fixture = new Fixture(helper, false)) {
            var method = ColonyLabor.class.getDeclaredMethod("plan", ServerLevel.class);
            method.setAccessible(true);
            method.invoke(fixture.labor, helper.getLevel());
            helper.assertTrue(fixture.plan().inputs() == PlanInputs.EMPTY, "cold pipeline supplies wanted=false");
            AtomicInteger translations = new AtomicInteger();
            RefusalKind why = () -> {
                translations.incrementAndGet();
                return "folkways.assistant.shortage.unknown";
            };
            UUID node = UUID.randomUUID();
            PlanningFixture.apply(fixture.labor, helper.getLevel(), List.of(new Message.Failed(node, why)),
                    WeaveDelta.NONE);
            helper.assertTrue(translations.get() == 0, "event capture never formats refusal keys");
            helper.assertTrue(fixture.plan().events().getLast().why() == why, "event retains raw RefusalKind");
            JsonObject args = new JsonObject();
            args.addProperty("node", node.toString());
            var first = fixture.read("plan_events", args);
            helper.assertTrue(first.get("warming").getAsBoolean() && translations.get() == 1,
                    "event reading opens demand and resolves its raw reason");
            method.invoke(fixture.labor, helper.getLevel());
            helper.assertFalse(fixture.read("plan_inputs", new JsonObject()).get("warming").getAsBoolean(),
                    "ColonyLabor and Pipeline pass wanted to the next existing planning round");
            long now = helper.getLevel().getGameTime();
            fixture.plan().demand(now + 10);
            helper.assertTrue(fixture.plan().wanted(now + 6000) && !fixture.plan().wanted(now + 6010),
                    "further reads extend the demand window");
            for (String name : List.of("plan_nodes", "plan_node", "plan_inputs", "plan_events", "trace")) {
                helper.assertTrue(ScriptApi.help(name, false).contains("warming=True"), "help explains warming");
            }
        }
        helper.succeed();
    }

    private static List<Long> summaryCounts(Planner planner) throws Exception {
        var field = Planner.class.getDeclaredField("weaver");
        field.setAccessible(true);
        Object weaver = field.get(planner);
        var tasks = weaver.getClass().getDeclaredField("taskSummaries");
        tasks.setAccessible(true);
        var costs = weaver.getClass().getDeclaredField("costs");
        costs.setAccessible(true);
        Object choices = costs.get(weaver);
        var processes = choices.getClass().getDeclaredField("processSummaries");
        processes.setAccessible(true);
        return List.of(tasks.getLong(weaver), processes.getLong(choices));
    }

    private static Work work(PlanningFixture scene, boolean missing) {
        var spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(scene.target),
                Stances.at(scene.target), Workload.Once.of(1)).vocation(Vocations.required(Vocations.HAULING));
        if (missing) {
            spec.needs(new Need(Goods.specOf(Items.DIAMOND.getDefaultInstance()), 1));
        }
        return new Work(spec.done());
    }

    private static JsonObject id(UUID id) {
        JsonObject args = new JsonObject();
        args.addProperty("id", id.toString());
        return args;
    }

    private static JsonObject find(JsonArray rows, String key, UUID id) {
        return rows.asList().stream().map(row -> row.getAsJsonObject())
                .filter(row -> row.get(key).getAsString().equals(id.toString())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + id + " in " + rows));
    }

    private static void error(GameTestHelper helper, Fixture fixture, String tool, JsonObject args, String code) {
        Purity.assertPure(helper, tool + " refuses " + code, () -> helper.assertTrue(
                ToolRegistry.READS.execute(fixture.reads.player, tool, args).data().get("error").getAsString()
                        .equals(code), code));
    }

    private static final class Fixture implements AutoCloseable {

        private final GameTestHelper helper;
        private final ReadFixture reads;
        private final ColonyLabor labor;
        private final Map<Object, ColonyLabor> sites;
        private final Object key;

        private Fixture(GameTestHelper helper) throws Exception {
            this(helper, true);
        }

        @SuppressWarnings("unchecked")
        private Fixture(GameTestHelper helper, boolean demand) throws Exception {
            this.helper = helper;
            reads = new ReadFixture(helper);
            var running = Labor.class.getDeclaredField("running");
            running.setAccessible(true);
            var field = Labor.class.getDeclaredField("sites");
            field.setAccessible(true);
            sites = (Map<Object, ColonyLabor>) field.get(running.get(null));
            var type = Class.forName(Labor.class.getName() + "$Site");
            var constructor = type.getDeclaredConstructor(UUID.class, ResourceKey.class);
            constructor.setAccessible(true);
            key = constructor.newInstance(reads.colony.id(), helper.getLevel().dimension());
            labor = new ColonyLabor(ColonyData.find(helper.getLevel().getServer(), reads.colony.id()).orElseThrow(),
                    helper.getLevel().dimension());
            sites.put(key, labor);
            if (demand) {
                plan().demand(helper.getLevel().getGameTime());
            }
        }

        private LaborPlan plan() {
            return LaborPlan.of(helper.getLevel(), reads.colony.id()).orElseThrow();
        }

        private JsonObject read(String name, JsonObject args) {
            ToolReply[] reply = {null};
            Purity.assertPure(helper, name, () -> {
                reply[0] = ToolRegistry.READS.execute(reads.player, name, args);
                helper.assertFalse(reply[0].data().has("error"), "read succeeded: " + reply[0].data());
            });
            return reply[0].data();
        }

        private void woven(List<Message> batch, Planner.Woven woven) throws Exception {
            var method = ColonyLabor.class.getDeclaredMethod("woven", ServerLevel.class, List.class,
                    Planner.Woven.class);
            method.setAccessible(true);
            method.invoke(labor, helper.getLevel(), batch, woven);
        }

        @Override
        public void close() throws Exception {
            sites.remove(key);
            var close = ColonyLabor.class.getDeclaredMethod("close");
            close.setAccessible(true);
            close.invoke(labor);
            reads.close();
        }
    }
}
