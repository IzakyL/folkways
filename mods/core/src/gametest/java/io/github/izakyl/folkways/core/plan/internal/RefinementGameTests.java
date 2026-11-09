package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.extension.api.Declaring;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RefinementGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "refinement");
    private record Obligation(NodeSpec spec, int stage, AtomicBoolean ready) implements Intent { }
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(RefinementGameTests.class); }
    @SubscribeEvent
    public static void declare(Declaring event) { event.refinement(rule(RefinementGameTests::elaborate)); }

    private static Refinement rule(Function<Intent, Optional<Grown>> expand) {
        return new Refinement() {
            public ResourceLocation id() { return OWNER; }
            public Optional<Grown> expand(Intent intent) { return expand.apply(intent); }
        };
    }
    private static NodeSpec spec(WorldPos at) {
        return NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.WHEREVER,
            Workload.Once.of(1)).done();
    }
    private static WorldPos at(GameTestHelper helper) {
        return WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1)));
    }
    private static NodeSpec renamed(NodeSpec s, UUID id) {
        return NodeSpec.of(id, s.owner(), s.site(), s.stances(), s.workload()).done();
    }
    private static Optional<Grown> elaborate(Intent intent) {
        if (!(intent instanceof Obligation o)) {
            return Optional.empty();
        }
        if (o.stage() == 0) {
            return o.ready().get() ? Optional.of(Grown.of(Nodes.done(o.spec()))) : Optional.empty();
        }
        UUID child = UUID.nameUUIDFromBytes((o.id() + ":" + o.stage()).getBytes(StandardCharsets.UTF_8));
        return Optional.of(Grown.then(Nodes.done(renamed(o.spec(), child)),
            new Obligation(o.spec(), o.stage() - 1, o.ready())).byOneWorker());
    }
    private static void rejects(GameTestHelper helper, Runnable operation) {
        boolean rejected = false;
        try { operation.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        helper.assertTrue(rejected, "invalid graph substitution must be rejected");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void recursivelyRefinesAndRewiresBoundaries(GameTestHelper helper) {
        WorldPos at = at(helper);
        Node before = Nodes.done(spec(at));
        Node after = Nodes.done(spec(at));
        var intent = new Obligation(spec(at), 2, new AtomicBoolean(true));
        var graph = new Grown(List.of(before, intent, after), List.of(new Before(before.id(), intent.id()),
            new Before(intent.id(), after.id())));
        Grown result = new Refining(List.of(rule(RefinementGameTests::elaborate))).expand(graph).orElseThrow().graph();
        helper.assertTrue(result.nodes().size() == 5 && result.nodes().stream().noneMatch(Intent.class::isInstance),
            "two layers must become executable nodes");
        List<UUID> chain = result.nodes().stream().map(Node::id).toList();
        for (int i = 1; i < chain.size(); i++) {
            helper.assertTrue(result.links().contains(new Before(chain.get(i - 1), chain.get(i))),
                "incoming dependency must move to expansion entry, outgoing dependency must stay on completion");
        }
        helper.assertTrue(result.workerGroups().stream().anyMatch(g -> g.containsAll(chain.subList(1, 4)))
            && result.workerGroups().stream().noneMatch(g -> g.contains(before.id()) || g.contains(after.id())),
            "local same-worker constraints must survive recursive substitution without binding unrelated work");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void pendingRefinementsPersistAndResumeWithoutReplacingTheAsk(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var ready = new AtomicBoolean();
        var intent = new Obligation(spec(f.source), 2, ready);
        var known = new Known(f.situation(new Stock(List.of(), Map.of()), List.of()), f.crew.hands());
        var planner = new Solving();
        Planned waiting = planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(intent))), 0);
        helper.assertTrue(waiting.weave().vertices().isEmpty() && waiting.schedule().tours().isEmpty(),
            "no partial or abstract obligation may execute");
        // The refinement names each layer's work after the ask and its stage, so its identities can be told ahead.
        Set<UUID> children = new HashSet<>();
        for (int stage = 2; stage > 0; stage--) {
            children.add(UUID.nameUUIDFromBytes((intent.id() + ":" + stage).getBytes(StandardCharsets.UTF_8)));
        }
        Planned unchanged = planner.handle(known, List.of(), 1);
        helper.assertTrue(unchanged.weave().vertices().isEmpty(), "an idle solve leaves the half-refined ask off the plan");
        ready.set(true);
        Planned resumed = planner.handle(known, List.of(Message.ANSWERED), 2);
        helper.assertTrue(resumed.weave().vertices().size() == 3
            && resumed.weave().vertices().keySet().containsAll(children)
            && resumed.weave().vertices().containsKey(intent.id()), "resume using the same intermediate identities");
        helper.assertTrue(resumed.schedule().unassigned().isEmpty(), "refined fragment must be schedulable");
        for (UUID child : children) {
            planner.handle(known, List.of(new Message.Finished(child)), 3);
        }
        Planned done = planner.handle(known, List.of(new Message.Finished(intent.id()), Message.ANSWERED), 4);
        helper.assertTrue(done.weave().vertices().isEmpty(),
            "completion of anchor closes the original ask without regenerating children");
        planner.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void cancellationRemovesPendingAndExpandedWork(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var known = new Known(f.situation(new Stock(List.of(), Map.of()), List.of()), f.crew.hands());
        for (boolean ready : List.of(false, true)) {
            var intent = new Obligation(spec(f.source), 2, new AtomicBoolean(ready));
            var claims = new Claims();
            var planner = new Solving(claims, new Cooldowns());
            planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(intent))), 0);
            Planned gone = planner.handle(known, List.of(new Message.Withdrawn(OWNER, intent.id()), Message.ANSWERED), 1);
            helper.assertTrue(gone.weave().vertices().isEmpty() && claims.size() == 0,
                "withdrawal by original identity must remove all descendants and pending state");
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void unknownObligationDoesNotBlockIndependentRefinement(GameTestHelper helper) {
        WorldPos at = at(helper);
        var unknown = new Obligation(spec(at), 0, new AtomicBoolean());
        var known = new Obligation(spec(at), 1, new AtomicBoolean(true));
        Refining.Result result = new Refining(List.of(rule(RefinementGameTests::elaborate)))
            .reduce(new Grown(List.of(unknown, known), List.of()));
        helper.assertTrue(!result.complete() && result.graph().nodes().size() == 3
            && result.graph().nodes().stream().filter(Intent.class::isInstance).count() == 1,
            "unresolved intent must remain while independent rules continue narrowing");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aPendingAskDoesNotPreventOtherAsksFromExecuting(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        NodeSpec unknownSpec = spec(f.source);
        Intent unknown = new Intent() { public NodeSpec spec() { return unknownSpec; } };
        Node action = Nodes.done(spec(f.source));
        var known = new Known(f.situation(new Stock(List.of(), Map.of()), List.of()), f.crew.hands());
        var planner = new Solving();
        Planned result = planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(unknown)),
            new Message.Submitted(OWNER, Grown.of(action))), 0);
        helper.assertTrue(result.weave().vertices().keySet().equals(Set.of(action.id()))
            && result.schedule().tours().values().stream().anyMatch(t -> t.nodes().contains(action.id())),
            "unknown plugin relations must remain pending without blocking another ask");
        planner.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void rejectsLostBoundaryEscapedWorkAndIdentityCollision(GameTestHelper helper) {
        WorldPos at = at(helper);
        var intent = new Obligation(spec(at), 0, new AtomicBoolean(true));
        Node other = Nodes.done(spec(at));
        rejects(helper, () -> new Refining(List.of(rule(i -> Optional.of(Grown.of(other))))).expand(Grown.of(intent)));
        rejects(helper, () -> new Refining(List.of(rule(i -> Optional.of(
            new Grown(List.of(other, Nodes.done(intent.spec())), List.of()))))).expand(Grown.of(intent)));
        rejects(helper, () -> new Refining(List.of(rule(i -> Optional.of(
            Grown.then(other, Nodes.done(intent.spec())))))).expand(new Grown(List.of(other, intent), List.of())));
        rejects(helper, () -> new Grown(List.of(other, intent),
            List.of(new Before(other.id(), intent.id()), new Before(intent.id(), other.id()))));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void relationCanConstrainExistingNodesOutsideItsReplacement(GameTestHelper helper) {
        WorldPos at = at(helper);
        Node first = Nodes.done(spec(at));
        Node second = Nodes.done(spec(at));
        var relation = new Obligation(spec(at), 0, new AtomicBoolean(true));
        Grown original = new Grown(List.of(first, second, relation), List.of());
        Refinement rule = new Refinement() {
            public ResourceLocation id() { return OWNER; }
            public Optional<Change> rewrite(Intent intent, Grown graph) {
                helper.assertTrue(graph.nodes().contains(first) && graph.nodes().contains(second),
                    "rule can inspect the surrounding graph without core interpreting its relation");
                return Optional.of(new Change(Grown.of(Nodes.done(intent.spec())),
                    List.of(new Before(first.id(), second.id()), new Before(second.id(), intent.id())),
                    List.of(Set.of(first.id(), second.id()))));
            }
        };
        Grown result = new Refining(List.of(rule)).expand(original).orElseThrow().graph();
        helper.assertTrue(result.workerGroups().equals(List.of(Set.of(first.id(), second.id())))
            && result.links().contains(new Before(first.id(), second.id())),
            "cross-node constraints must survive without binding the relation's own completion marker");
        Refinement cyclic = new Refinement() {
            public ResourceLocation id() { return OWNER; }
            public Optional<Change> rewrite(Intent intent, Grown graph) {
                return Optional.of(new Change(Grown.of(Nodes.done(intent.spec())),
                    List.of(new Before(second.id(), first.id())), List.of()));
            }
        };
        rejects(helper, () -> new Refining(List.of(cyclic)).expand(new Grown(original.nodes(),
            List.of(new Before(first.id(), second.id())))));
        Refinement dangling = new Refinement() {
            public ResourceLocation id() { return OWNER; }
            public Optional<Change> rewrite(Intent intent, Grown graph) {
                return Optional.of(new Change(Grown.of(Nodes.done(intent.spec())), List.of(),
                    List.of(Set.of(UUID.randomUUID(), first.id()))));
            }
        };
        rejects(helper, () -> new Refining(List.of(dangling)).expand(original));
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void acceptsTerminationExactlyAtRefinementLimit(GameTestHelper helper) {
        WorldPos at = at(helper);
        var intent = new Obligation(spec(at), 255, new AtomicBoolean(true));
        Grown result = new Refining(List.of(rule(RefinementGameTests::elaborate))).expand(Grown.of(intent)).orElseThrow().graph();
        helper.assertTrue(result.nodes().size() == 256 && result.nodes().stream().noneMatch(Intent.class::isInstance),
            "a terminating rule at the exact limit must not be treated as an infinite expansion");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void boundsNonTerminatingRules(GameTestHelper helper) {
        WorldPos at = at(helper);
        var intent = new Obligation(spec(at), 0, new AtomicBoolean(true));
        rejects(helper, () -> new Refining(List.of(rule(i -> Optional.of(Grown.of(i))))).expand(Grown.of(intent)));
        helper.succeed();
    }
}
