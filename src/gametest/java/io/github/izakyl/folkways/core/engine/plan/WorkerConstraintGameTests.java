package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.body.Keenness;
import io.github.izakyl.folkways.core.api.resident.body.Licence;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkerConstraintGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(WorkerConstraintGameTests.class); }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workNoOneWasFitForGrowsOnceSomeoneIs(GameTestHelper helper) throws Exception {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        var unlicensed = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, 1, List.of(), List.of(),
            Optional.empty())));
        var spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1))
            .vocation(Vocations.required(Vocations.HAULING)).done();
        Node work = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        var situation = f.situation(new Stock(List.of(), Map.of()), List.of());
        var planner = new Planner(new Claims(), new Cooldowns());
        try {
            planner.handle(new Known(situation, unlicensed.hands()),
                List.of(new Message.Submitted(ResourceLocation.withDefaultNamespace("probe"), Grown.of(work))), 0);
            for (long tick = 1; tick <= 200; tick += 7) {
                planner.handle(new Known(situation, unlicensed.hands()), List.of(Message.ANSWERED), tick);
            }
            Solved fit = planner.handle(new Known(situation, f.crew.hands()), List.of(), 201);
            helper.assertTrue(fit.weave().vertices().containsKey(work.id()),
                "refused over and over while no one was licensed, the work grows in the round someone is");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void choosesWorkerForEntireConstraintGroup(GameTestHelper helper) throws Exception {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var hauling = Vocations.required(Vocations.HAULING);
        var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways", "building"));
        var constructor = Licence.class.getDeclaredConstructor(Vocation.class, Keenness.class);
        constructor.setAccessible(true);
        var haul = constructor.newInstance(hauling, Keenness.FIRST);
        var build = constructor.newInstance(building, Keenness.FIRST);
        var lessKeenHaul = constructor.newInstance(hauling, Keenness.THIRD);
        var one = f.crew.hands().getFirst();
        var two = g.crew.hands().getFirst();
        var crew = new Crew(List.of(
            new Crew.Hand(one.who(), one.at(), 6, 6, 1, List.of(haul), List.of(), Optional.empty()),
            new Crew.Hand(two.who(), two.at(), 6, 6, 1, List.of(lessKeenHaul, build), List.of(), Optional.empty())));
        var takeSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).vocation(hauling).done();
        var workSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).vocation(building).done();
        Node take = new Node() {
            public NodeSpec spec() { return takeSpec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        Node work = new Node() {
            public NodeSpec spec() { return workSpec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        var weave = new Weave(Map.of(take.id(), new Vertex(take.id(), take, Placement.NOWHERE),
            work.id(), new Vertex(work.id(), work, Placement.NOWHERE)),
            List.of(new Before(take.id(), work.id())),
            List.of(Set.of(take.id(), work.id())));
        var schedule = new Tours().schedule(weave, crew, f.ways, 0, true);
        helper.assertTrue(!schedule.tours().containsKey(one.id())
            && schedule.tours().containsKey(two.id()) && schedule.unassigned().isEmpty()
            && schedule.tours().get(two.id()).nodes().equals(List.of(take.id(), work.id())),
            "the first assignment must intersect licences across the whole same-worker group");
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void appliesTransitiveWorkerPinsAcrossGroups(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        UUID chosen = g.crew.hands().getFirst().id();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("group"),
                new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
            boolean last = i == 2;
            nodes.add(new Node() {
                public NodeSpec spec() { return spec; }
                public Optional<UUID> worker() { return last ? Optional.of(chosen) : Optional.empty(); }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            });
        }
        UUID first = nodes.get(0).id(), middle = nodes.get(1).id(), last = nodes.get(2).id();
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        nodes.forEach(n -> vertices.put(n.id(), new Vertex(n.id(), n, Placement.NOWHERE)));
        var weave = new Weave(vertices, List.of(new Before(first, middle), new Before(middle, last)),
             List.of(Set.of(first, middle), Set.of(middle, last)));
        var schedule = new Tours().schedule(weave,
            new Crew(List.of(f.crew.hands().getFirst(), g.crew.hands().getFirst())), f.ways, 0, true);
        helper.assertTrue(schedule.unassigned().isEmpty() && schedule.tours().size() == 1
            && schedule.tours().get(chosen).nodes().equals(List.of(first, middle, last)),
            "a pin on the final member must constrain the entire transitive group before pickup");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void smallLoadsOfOneKindShareTheirStackInThePack(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var one = f.crew.hands().getFirst();
        var crew = new Crew(List.of(new Crew.Hand(one.who(), one.at(), 2, 2, 1, one.licences(),
            List.of(), Optional.empty())));
        var log = ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_log"));
        var planks = ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_planks"));
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        List<Before> links = new ArrayList<>();
        List<UUID> order = new ArrayList<>();
        NodeSpec craftSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("craft"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
        Node craft = new Node() {
            public NodeSpec spec() { return craftSpec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        for (int i = 0; i < 8; i++) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("collect"),
                new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
            Node collect = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            vertices.put(collect.id(), new Vertex(collect.id(), collect,
                new Placement(Set.of(), List.of(new Amount(log, 1)), List.of(), List.of())));
            links.add(new Before(collect.id(), craft.id()));
            order.add(collect.id());
        }
        vertices.put(craft.id(), new Vertex(craft.id(), craft, new Placement(Set.of(),
            List.of(new Amount(log, -8), new Amount(planks, 32)), List.of(), List.of())));
        order.add(craft.id());
        var schedule = new Tours().schedule(new Weave(vertices, links, List.of(Set.copyOf(order))), crew, f.ways, 0,
            true);
        helper.assertTrue(schedule.unassigned().isEmpty() && schedule.tours().containsKey(one.id())
                && schedule.tours().get(one.id()).nodes().size() == 9,
            "eight single logs fill one cell of a two-cell pack, not eight");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aHandHoldingEndlessWorkOffItsRouteTakesNothingElse(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var keeping = Vocations.required(Vocations.HAULING);
        NodeSpec postSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("post"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, (Workload.Continuous) (level, who) -> true)
            .vocation(keeping).done();
        NodeSpec chore = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("chore"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
        Node post = new Node() {
            public NodeSpec spec() { return postSpec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        Node work = new Node() {
            public NodeSpec spec() { return chore; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        var one = f.crew.hands().getFirst();
        var two = g.crew.hands().getFirst();
        var sitter = new Crew.Hand(one.who(), one.at(), 6, 6, 1, List.of(), List.of(), Optional.of(post.id()));
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        vertices.put(post.id(), new Vertex(post.id(), post, Placement.NOWHERE));
        vertices.put(work.id(), new Vertex(work.id(), work, Placement.NOWHERE));
        var weave = new Weave(vertices, List.of(), List.of());
        var alone = new Tours().schedule(weave, new Crew(List.of(sitter)), f.ways, 0, true);
        helper.assertTrue(!alone.tours().containsKey(one.id())
                && alone.unassigned().stream().anyMatch(u -> u.node().equals(work.id())),
            "a hand sitting at a post it can no longer be planned for is given no other work");
        var both = new Tours().schedule(weave, new Crew(List.of(sitter, two)), f.ways, 0, true);
        helper.assertTrue(!both.tours().containsKey(one.id()) && both.tours().containsKey(two.id())
                && both.tours().get(two.id()).nodes().equals(List.of(work.id())),
            "the work goes to a hand that is free");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void conflictingWorkerPinsLeaveWholeGroupUnassigned(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        List<Node> nodes = new ArrayList<>();
        for (var hand : List.of(f.crew.hands().getFirst(), g.crew.hands().getFirst())) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("group"),
                new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
            nodes.add(new Node() {
                public NodeSpec spec() { return spec; }
                public Optional<UUID> worker() { return Optional.of(hand.id()); }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            });
        }
        UUID first = nodes.get(0).id(), last = nodes.get(1).id();
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        nodes.forEach(n -> vertices.put(n.id(), new Vertex(n.id(), n, Placement.NOWHERE)));
        var weave = new Weave(vertices, List.of(new Before(first, last)),
            List.of(Set.of(first, last)));
        var schedule = new Tours().schedule(weave,
            new Crew(List.of(f.crew.hands().getFirst(), g.crew.hands().getFirst())), f.ways, 0, true);
        helper.assertTrue(schedule.tours().isEmpty() && schedule.unassigned().size() == 2
            && schedule.unassigned().stream().allMatch(u -> u.why() == Unassigned.Reason.SAME_WORKER_SPLIT),
            "contradictory group must not start a pickup that nobody can complete");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFetchIsGrownOnlyForSomeoneWhoCanAlsoDoItsConsumer(GameTestHelper helper) throws Exception {
        var scene = new Wall(helper);
        var solver = new PlanningFixture.Rounds();
        try {
            var solved = solver.solve(scene.situation(), scene.cramped, scene.goals(), List.of(), 0);
            helper.assertTrue(solved.weave().vertices().isEmpty() && solved.schedule().unassigned().isEmpty()
                    && solved.diagnosis().shortfalls().stream().anyMatch(gap -> gap.why() instanceof Why.NoHand),
                "no fetch is grown that no one could carry on to the wall: " + solved.diagnosis());
        } finally {
            solver.closed();
        }
        var again = new PlanningFixture.Rounds();
        try {
            var solved = again.solve(scene.situation(), scene.roomy, scene.goals(), List.of(), 0);
            var tour = solved.schedule().tours().get(scene.builder);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty() && solved.schedule().unassigned().isEmpty()
                    && tour != null && tour.nodes().getLast().equals(scene.wall.id()),
                "with room in the builder's pack, the builder fetches the stone and builds: " + solved.diagnosis());
        } finally {
            again.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workNoOneIsFitForAnyMoreGoesBackToWhatAskedForIt(GameTestHelper helper) throws Exception {
        var scene = new Wall(helper);
        var solver = new PlanningFixture.Rounds();
        try {
            var planned = solver.solve(scene.situation(), scene.roomy, scene.goals(), List.of(), 0);
            helper.assertTrue(planned.schedule().tours().containsKey(scene.builder),
                "the builder is given the fetch and the wall: " + planned.diagnosis());
            var shrunk = solver.solve(scene.situation(), scene.cramped, scene.goals(), List.of(), 1);
            helper.assertTrue(shrunk.weave().vertices().values().stream()
                    .noneMatch(vertex -> vertex.node() instanceof TransferNode)
                    && shrunk.schedule().unassigned().stream().noneMatch(nobody -> nobody.why().structural()),
                "once the builder's pack is too small, the fetch it was given goes, not left to no one: "
                    + shrunk.diagnosis());
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    // A wall only a builder may build, of two stacks of stone carried from the source. The hauler has the room but
    // not the trade; the builder has the trade, and a pack of six cells or of one.
    private static final class Wall {

        final PlanningFixture f;
        final Node wall;
        final UUID builder;
        final Crew roomy;
        final Crew cramped;

        Wall(GameTestHelper helper) throws Exception {
            f = new PlanningFixture(helper);
            var g = new PlanningFixture(helper);
            var hauling = Vocations.required(Vocations.HAULING);
            var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways", "building"));
            var constructor = Licence.class.getDeclaredConstructor(Vocation.class, Keenness.class);
            constructor.setAccessible(true);
            var haul = constructor.newInstance(hauling, Keenness.FIRST);
            var build = constructor.newInstance(building, Keenness.FIRST);
            var one = f.crew.hands().getFirst();
            var two = g.crew.hands().getFirst();
            builder = two.id();
            var builds = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("build"),
                new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1)).vocation(building)
                .needs(new Need(Goods.specOf(new ItemStack(Items.COBBLESTONE)), 128, true)).done();
            wall = new Node() {
                public NodeSpec spec() { return builds; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
            };
            var hauler = new Crew.Hand(one.who(), one.at(), 6, 6, 1, List.of(haul), List.of(), Optional.empty());
            roomy = new Crew(List.of(hauler,
                new Crew.Hand(two.who(), two.at(), 6, 6, 1, List.of(haul, build), List.of(), Optional.empty())));
            cramped = new Crew(List.of(hauler,
                new Crew.Hand(two.who(), two.at(), 1, 1, 1, List.of(haul, build), List.of(), Optional.empty())));
        }

        Situation situation() {
            return f.situation(new Stock(List.of(
                new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64)),
                new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64))),
                Map.of(new Stash(f.source), 25, new Stash(f.target), 27)), List.of());
        }

        List<Grown> goals() {
            return List.of(Grown.of(wall));
        }
    }
}
