package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.resident.api.Licence;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.transport.api.TransportPublication;
import io.github.izakyl.folkways.core.transport.api.TransportSnapshot;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.vocation.api.Keenness;
import io.github.izakyl.folkways.core.vocation.api.Vocation;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.ToolNeed;
import io.github.izakyl.folkways.core.work.api.ToolPolicy;
import io.github.izakyl.folkways.core.work.api.ToolUse;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.vanilla.person.PersonBody;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WorkerConstraintGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(WorkerConstraintGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void multiVocationFitnessUsesHeldToolsAcrossVocations(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper);
        var base = fixture.crew.hands().getFirst();
        var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
        var patrol = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "patrolling"));
        var resident = PersonBody.RESIDENT.get().create(helper.getLevel());
        var licences = List.of(resident.licences().of(building).orElseThrow(), resident.licences().of(patrol).orElseThrow());
        var rules = new ToolPolicy(Map.of(
            building.id(), Set.of(Items.STONE_AXE), patrol.id(), Set.of(Items.IRON_AXE)));
        var hand = new Crew.Hand(base.who(), base.at(), 6, 6, 1, licences,
            List.of(new ItemStack(Items.STONE_AXE)), Optional.empty(), List.of(), rules);
        var step = NodeSpec.of(UUID.randomUUID(), building.id(), new WorkSite.AtBlock(fixture.source),
            Stances.WHEREVER, Workload.Once.of(1)).vocation(patrol)
            .tools(new ToolUse(new ToolNeed.Ability(ItemAbilities.AXE_DIG), 1)).done();
        var work = new Fitness.Work(List.of(step), Set.of(), 0);
        helper.assertTrue(Fitness.of(hand, work).isEmpty(),
            "another licensed vocation's held axe qualifies the step regardless of its acquisition list");
        var equipped = new Crew.Hand(hand.who(), hand.at(), 6, 6, 1, licences,
            List.of(new ItemStack(Items.STONE_AXE), new ItemStack(Items.IRON_AXE)), Optional.empty(), List.of(), rules);
        helper.assertTrue(Fitness.of(equipped, work).isEmpty(), "either held axe qualifies the same resident");
        resident.mob().discard();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void workNoOneWasFitForGrowsOnceSomeoneIs(GameTestHelper helper) throws Exception {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        var unlicensed = new Crew(List.of(hand(hand, 6, List.of())));
        var spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1))
            .vocation(Vocations.required(Vocations.HAULING)).done();
        Node work = Nodes.done(spec);
        var situation = f.situation(new Stock(List.of(), Map.of()), List.of());
        var planner = new Solving();
        try {
            planner.handle(new Known(situation, unlicensed.hands()),
                List.of(new Message.Submitted(ResourceLocation.withDefaultNamespace("probe"), Grown.of(work))), 0);
            for (long tick = 1; tick <= 200; tick += 7) {
                planner.handle(new Known(situation, unlicensed.hands()), List.of(Message.ANSWERED), tick);
            }
            Planned fit = planner.handle(new Known(situation, f.crew.hands()), List.of(), 201);
            helper.assertTrue(fit.weave().vertices().containsKey(work.id()),
                "refused over and over while no one was licensed, the work grows in the round someone is");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void choosesWorkerForEntireConstraintGroup(GameTestHelper helper) throws Exception {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var hauling = Vocations.required(Vocations.HAULING);
        var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
        var haul = licence(hauling, Keenness.FIRST);
        var build = licence(building, Keenness.FIRST);
        var lessKeenHaul = licence(hauling, Keenness.THIRD);
        var one = f.crew.hands().getFirst();
        var two = g.crew.hands().getFirst();
        var crew = new Crew(List.of(hand(one, 6, List.of(haul)), hand(two, 6, List.of(lessKeenHaul, build))));
        var takeSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).vocation(hauling).done();
        var workSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("probe"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).vocation(building).done();
        Node take = Nodes.done(takeSpec);
        Node work = Nodes.done(workSpec);
        var weave = Shown.delta(Shown.vertices(take, work), List.of(new Before(take.id(), work.id())),
            List.of(Set.of(take.id(), work.id())));
        var schedule = Shown.schedule(new Tours(), weave, crew, f.ways, 0, true);
        helper.assertTrue(!schedule.tours().containsKey(one.id())
            && schedule.tours().containsKey(two.id()) && schedule.unassigned().isEmpty()
            && schedule.tours().get(two.id()).nodes().equals(List.of(take.id(), work.id())),
            "the first assignment must intersect licences across the whole same-worker group");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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
        var weave = Shown.delta(Shown.vertices(nodes.toArray(Node[]::new)),
            List.of(new Before(first, middle), new Before(middle, last)),
            List.of(Set.of(first, middle), Set.of(middle, last)));
        var schedule = Shown.schedule(new Tours(), weave,
            new Crew(List.of(f.crew.hands().getFirst(), g.crew.hands().getFirst())), f.ways, 0, true);
        helper.assertTrue(schedule.unassigned().isEmpty() && schedule.tours().size() == 1
            && schedule.tours().get(chosen).nodes().equals(List.of(first, middle, last)),
            "a pin on the final member must constrain the entire transitive group before pickup");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void smallLoadsOfOneKindShareTheirStackInThePack(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var one = f.crew.hands().getFirst();
        var crew = new Crew(List.of(hand(one, 2, one.licences())));
        var log = ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_log"));
        var planks = ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_planks"));
        Map<UUID, Vertex> vertices = new LinkedHashMap<>();
        List<Before> links = new ArrayList<>();
        List<UUID> order = new ArrayList<>();
        NodeSpec craftSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("craft"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
        Node craft = Nodes.done(craftSpec);
        for (int i = 0; i < 8; i++) {
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("collect"),
                new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
            Node collect = Nodes.done(spec);
            vertices.put(collect.id(), new Vertex(collect.id(), collect,
                new Placement(Set.of(), List.of(new Amount(log, 1)), List.of(), List.of())));
            links.add(new Before(collect.id(), craft.id()));
            order.add(collect.id());
        }
        vertices.put(craft.id(), new Vertex(craft.id(), craft, new Placement(Set.of(),
            List.of(new Amount(log, -8), new Amount(planks, 32)), List.of(), List.of())));
        order.add(craft.id());
        var schedule = Shown.schedule(new Tours(), Shown.delta(vertices, links, List.of(Set.copyOf(order))), crew,
            f.ways, 0, true);
        helper.assertTrue(schedule.unassigned().isEmpty() && schedule.tours().containsKey(one.id())
                && schedule.tours().get(one.id()).nodes().size() == 9,
            "eight single logs fill one cell of a two-cell pack, not eight");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aHandHoldingEndlessWorkOffItsRouteTakesNothingElse(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var keeping = Vocations.required(Vocations.HAULING);
        NodeSpec postSpec = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("post"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, (Workload.Continuous) (level, who) -> true)
            .vocation(keeping).done();
        NodeSpec chore = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("chore"),
            new WorkSite.AtBlock(f.source), Stances.WHEREVER, Workload.Once.of(1)).done();
        Node post = Nodes.done(postSpec);
        Node work = Nodes.done(chore);
        var one = f.crew.hands().getFirst();
        var two = g.crew.hands().getFirst();
        var sitter = new Crew.Hand(one.who(), one.at(), 6, 6, 1, List.of(), List.of(), Optional.of(post.id()));
        var weave = Shown.delta(post, work);
        var alone = Shown.schedule(new Tours(), weave, new Crew(List.of(sitter)), f.ways, 0, true);
        helper.assertTrue(!alone.tours().containsKey(one.id())
                && alone.unassigned().stream().anyMatch(u -> u.node().equals(work.id())),
            "a hand sitting at a post it can no longer be planned for is given no other work");
        var both = Shown.schedule(new Tours(), weave, new Crew(List.of(sitter, two)), f.ways, 0, true);
        helper.assertTrue(!both.tours().containsKey(one.id()) && both.tours().containsKey(two.id())
                && both.tours().get(two.id()).nodes().equals(List.of(work.id())),
            "the work goes to a hand that is free");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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
        var weave = Shown.delta(Shown.vertices(nodes.toArray(Node[]::new)), List.of(new Before(first, last)),
            List.of(Set.of(first, last)));
        var schedule = Shown.schedule(new Tours(), weave,
            new Crew(List.of(f.crew.hands().getFirst(), g.crew.hands().getFirst())), f.ways, 0, true);
        helper.assertTrue(schedule.tours().isEmpty() && schedule.unassigned().size() == 2
            && schedule.unassigned().stream().allMatch(u -> u.why() == Unassigned.Reason.SAME_WORKER_SPLIT),
            "contradictory group must not start a pickup that nobody can complete");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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

    // A builder fetches the stone for a wall that needs a pickaxe, and then the pickaxe breaks. The wall was bound to
    // them for the stone they carry; another builder with a pickaxe takes it on, with stone fetched afresh.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void workBoundToACarrierWhoCanNoLongerDoItGoesToSomeoneWhoCan(GameTestHelper helper)
            throws Exception {
        carrierLosesTheTool(helper, false);
    }

    // The same, but the pickaxe breaks at the wall: while in hand the wall stays theirs, and once let go of it is
    // looked at again, though nothing more about the crew changes.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void workLetGoOfByAHandWhoseToolBrokeAtItGoesToSomeoneWhoCan(GameTestHelper helper)
            throws Exception {
        carrierLosesTheTool(helper, true);
    }

    private static void carrierLosesTheTool(GameTestHelper helper, boolean atTheWork) throws Exception {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
        var hauling = Vocations.required(Vocations.HAULING);
        var licences = List.of(licence(building, Keenness.FIRST), licence(hauling, Keenness.FIRST));
        var stone = Goods.specOf(new ItemStack(Items.COBBLESTONE));
        var wall = Nodes.done(NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("build"),
                new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1)).vocation(building)
            .tools(new ToolUse(new ToolNeed.Ability(ItemAbilities.PICKAXE_DIG), 1))
            .needs(new Need(stone, 64, true)).done());
        var one = f.crew.hands().getFirst();
        var two = g.crew.hands().getFirst();
        var pick = List.of(new ItemStack(Items.WOODEN_PICKAXE));
        var stock = new Stock(List.of(
            new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64)),
            new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64))),
            Map.of(new Stash(f.source), 25, new Stash(f.target), 27));
        var solver = new PlanningFixture.Rounds();
        try {
            var both = new Crew(List.of(
                new Crew.Hand(one.who(), one.at(), 6, 6, 1, licences, pick, Optional.empty()),
                new Crew.Hand(two.who(), two.at(), 6, 6, 1, licences, pick, Optional.empty())));
            var planned = solver.solve(f.situation(stock, List.of()), both, List.of(Grown.of(wall)), List.of(), 0);
            UUID first = planned.schedule().tours().values().stream()
                .filter(tour -> tour.nodes().contains(wall.id())).map(Tour::resident).findFirst().orElse(null);
            helper.assertTrue(first != null, "someone is given the wall: " + planned.diagnosis());
            UUID fetch = planned.schedule().tours().get(first).nodes().stream()
                .filter(node -> planned.weave().vertices().get(node).node() instanceof TransferNode)
                .findFirst().orElse(null);
            helper.assertTrue(fetch != null, "the wall's builder fetches its stone: " + planned.diagnosis());
            UUID other = first.equals(one.id()) ? two.id() : one.id();
            var carried = List.of(new ItemStack(Items.COBBLESTONE, 64));
            var left = new Stock(List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64))),
                Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
            Crew.Hand carrier = first.equals(one.id()) ? one : two;
            Crew.Hand bystander = first.equals(one.id()) ? two : one;
            // Taking the fetch up binds the whole of what it goes with to the hand, the wall too.
            solver.solve(f.situation(stock, List.of()), new Crew(List.of(
                    new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, pick, Optional.of(fetch)),
                    new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                List.of(Grown.of(wall)), List.of(), 1);
            var fetched = solver.solve(f.situation(left, List.of()), new Crew(List.of(
                    new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, pick, Optional.empty(), carried),
                    new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                List.of(Grown.of(wall)), List.of(new Message.Finished(fetch)), 1);
            helper.assertTrue(Optional.ofNullable(fetched.schedule().tours().get(first))
                    .filter(tour -> tour.nodes().contains(wall.id())).isPresent(),
                "with the stone in their pack, the wall stays the carrier's: " + fetched.diagnosis());
            List<Message> letGo = List.of();
            if (atTheWork) {
                for (var tools : List.of(pick, List.<ItemStack>of())) {
                    var holding = solver.solve(f.situation(left, List.of()), new Crew(List.of(
                            new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, tools, Optional.of(wall.id()),
                                carried),
                            new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                        List.of(Grown.of(wall)), List.of(), 2);
                    helper.assertTrue(holding.weave().vertices().containsKey(wall.id()),
                        "the wall in hand stays on the plan: " + holding.diagnosis());
                }
                letGo = List.of(new Message.Released(wall.id()));
            }
            var broken = solver.solve(f.situation(left, List.of()), new Crew(List.of(
                    new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, List.of(), Optional.empty(), carried),
                    new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                List.of(Grown.of(wall)), letGo, 3);
            helper.assertTrue(broken.schedule().unassigned().stream()
                    .noneMatch(nobody -> nobody.why() == Unassigned.Reason.NO_TOOL),
                "a pickaxe gone, the wall is not left bound to one who cannot build it: " + broken.diagnosis());
            helper.assertTrue(Optional.ofNullable(broken.schedule().tours().get(other))
                    .filter(tour -> tour.nodes().contains(wall.id())).isPresent(),
                "the builder who still has a pickaxe is given the wall: " + broken.diagnosis());
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    // A builder already carries the stone, so the wall is fed from their pack and bound to them; their pickaxe
    // breaks at other work. The wall goes to the builder who still has one, fed from the store, and is not fed
    // from the first builder's pack again.
    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void workFedFromAPackWhoseCarrierCanNoLongerDoItGoesToSomeoneWhoCan(GameTestHelper helper)
            throws Exception {
        var f = new PlanningFixture(helper);
        var g = new PlanningFixture(helper);
        var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
        var hauling = Vocations.required(Vocations.HAULING);
        var licences = List.of(licence(building, Keenness.FIRST), licence(hauling, Keenness.FIRST));
        var wall = Nodes.done(NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("build"),
                new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1)).vocation(building)
            .tools(new ToolUse(new ToolNeed.Ability(ItemAbilities.PICKAXE_DIG), 1))
            .needs(new Need(Goods.specOf(new ItemStack(Items.COBBLESTONE)), 64, true)).done());
        var carrier = f.crew.hands().getFirst();
        var bystander = g.crew.hands().getFirst();
        var pick = List.of(new ItemStack(Items.WOODEN_PICKAXE));
        var carried = List.of(new ItemStack(Items.COBBLESTONE, 64));
        var stock = new Stock(List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.COBBLESTONE, 64))),
            Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
        // The store relays goods as every colony store does, so goods are routed as in play.
        var relay = new StorageTransport(new Stash(f.source), stock, Set.of(new Stand(f.source)),
            Set.of(new Stand(f.source)));
        var published = new TransportPublication(1, List.of(new TransportPublication.Entry(StorageTransport.ID,
            helper.getLevel().dimension(), new TransportPublication.Offering(relay,
                new TransportSnapshot(0, 0, TransportTravel.UNKNOWN, List.of()), true))));
        var situation = f.situation(stock, List.of(), published, 0);
        var solver = new PlanningFixture.Rounds();
        try {
            var planned = solver.solve(situation, new Crew(List.of(
                    new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, pick, Optional.empty(), carried),
                    new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                List.of(Grown.of(wall)), List.of(), 0);
            helper.assertTrue(Optional.ofNullable(planned.schedule().tours().get(carrier.id()))
                    .filter(tour -> tour.nodes().contains(wall.id())).isPresent(),
                "the wall is built from the stone the carrier has: " + planned.diagnosis());
            var broken = solver.solve(situation, new Crew(List.of(
                    new Crew.Hand(carrier.who(), carrier.at(), 6, 6, 1, licences, List.of(), Optional.empty(), carried),
                    new Crew.Hand(bystander.who(), bystander.at(), 6, 6, 1, licences, pick, Optional.empty()))),
                List.of(Grown.of(wall)), List.of(), 1);
            helper.assertTrue(broken.schedule().unassigned().stream()
                    .noneMatch(nobody -> nobody.why() == Unassigned.Reason.NO_TOOL),
                "a pickaxe gone, the wall is not left bound to one who cannot build it: " + broken.diagnosis());
            helper.assertTrue(Optional.ofNullable(broken.schedule().tours().get(bystander.id()))
                    .filter(tour -> tour.nodes().contains(wall.id())).isPresent(),
                "the builder who still has a pickaxe is given the wall: " + broken.diagnosis());
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void refitsOnlyTheIncompatiblePieceOfASplitGroup(GameTestHelper helper) throws Exception {
        try (var scene = new CargoGroup(helper)) {
            scene.pickUp(scene.stone, scene.first);
            scene.pickUp(scene.wood, scene.first);
            var binding = scene.bind();
            var first = scene.hand(scene.first, List.of(new ItemStack(Items.WOODEN_PICKAXE)),
                Optional.of(scene.stone.id()));
            var second = scene.hand(scene.second, scene.tools, Optional.empty());
            var crew = new Crew(List.of(first, second));
            scene.grow(crew);
            helper.assertTrue(scene.plan.grouped(scene.stone.id()).orElseThrow().contains(scene.wood.id())
                    && scene.first.id().equals(scene.plan.pins().get(scene.wood.id())),
                "the worker keeps both pieces while one is in hand");
            binding.unbind();
            var split = scene.grow(crew);
            helper.assertTrue(!split.fresh().contains(scene.stone.id()) && split.fresh().contains(scene.wood.id())
                    && scene.first.id().equals(scene.plan.pins().get(scene.stone.id())),
                "the split keeps the step in hand and grows only the incompatible step afresh");
            scene.weaver.released(scene.stone.id());
            var released = new Crew(List.of(scene.hand(scene.first, first.tools(), Optional.empty()), second));
            scene.grow(released);
            helper.assertTrue(scene.first.id().equals(scene.plan.pins().get(scene.stone.id())),
                "the compatible step still belongs to its carrier after release");
            scene.complete(released, scene.stone, scene.first, scene.wood, scene.second);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void refitsAGroupWhenTwoPacksSupplyIt(GameTestHelper helper) throws Exception {
        try (var scene = new CargoGroup(helper)) {
            scene.pickUp(scene.stone, scene.first);
            scene.pickUp(scene.wood, scene.second);
            var crew = scene.currentCrew();
            scene.grow(crew);
            helper.assertTrue(scene.first.id().equals(scene.plan.pins().get(scene.stone.id()))
                    && scene.second.id().equals(scene.plan.pins().get(scene.wood.id())),
                "each consumer is bound to the pack holding its own goods");
            scene.bind();
            var repaired = scene.grow(crew);
            helper.assertTrue(repaired.fresh().containsAll(Set.of(scene.stone.id(), scene.wood.id()))
                    && scene.plan.pins().isEmpty(),
                "new binding alone triggers refit and releases both conflicting cargo pins");
            var schedule = scene.schedule(crew);
            var stoneWorker = scene.assignedTo(schedule, scene.stone);
            var woodWorker = scene.assignedTo(schedule, scene.wood);
            helper.assertTrue(stoneWorker.id().equals(woodWorker.id()),
                "one resident takes both steps after the conflicting pack reservations are released");
            scene.complete(crew, scene.stone, stoneWorker, scene.wood, woodWorker);
        }
        helper.succeed();
    }

    // A separate growth can bind two consumers whose supplies have already been planned independently.
    private static final class CargoGroup implements AutoCloseable {

        final GameTestHelper helper;
        final PlanningFixture fixture;
        final Weaver weaver = new Weaver(new Claims(), new Cooldowns());
        final WeaveView plan = new WeaveView();
        final Crew.Hand first;
        final Crew.Hand second;
        final List<Licence> licences;
        final List<ItemStack> tools = List.of(new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.WOODEN_AXE));
        final Map<UUID, List<ItemStack>> packs = new LinkedHashMap<>();
        final Node stone;
        final Node wood;
        long tick;

        CargoGroup(GameTestHelper helper) throws Exception {
            this.helper = helper;
            fixture = new PlanningFixture(helper);
            first = fixture.crew.hands().getFirst();
            second = new PlanningFixture(helper).crew.hands().getFirst();
            var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
            licences = List.of(licence(building, Keenness.FIRST),
                licence(Vocations.required(Vocations.HAULING), Keenness.FIRST));
            stone = work(building, Items.COBBLESTONE, ItemAbilities.PICKAXE_DIG);
            wood = work(building, Items.OAK_PLANKS, ItemAbilities.AXE_DIG);
            var owner = ResourceLocation.withDefaultNamespace("build");
            weaver.submit(owner, Grown.of(stone));
            weaver.submit(owner, Grown.of(wood));
            grow(currentCrew());
        }

        private Node work(Vocation building, Item item, ItemAbility ability) {
            return Nodes.done(NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("build"),
                    new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), Workload.Once.of(1))
                .vocation(building).tools(new ToolUse(new ToolNeed.Ability(ability), 1))
                .needs(new Need(Goods.specOf(new ItemStack(item)), 64, true)).done());
        }

        Crew.Hand hand(Crew.Hand base, List<ItemStack> equipped, Optional<UUID> holding) {
            return new Crew.Hand(base.who(), base.at(), 6, 6, 1, licences, equipped, holding,
                List.copyOf(packs.getOrDefault(base.id(), List.of())));
        }

        Crew currentCrew() {
            return new Crew(List.of(hand(first, tools, Optional.empty()), hand(second, tools, Optional.empty())));
        }

        WeaveDelta grow(Crew crew) {
            var stock = new Stock(List.of(
                new Stock.Holding(new Stash(fixture.source), new ItemStack(Items.COBBLESTONE, 64)),
                new Stock.Holding(new Stash(fixture.source), new ItemStack(Items.OAK_PLANKS, 64))),
                Map.of(new Stash(fixture.source), 25, new Stash(fixture.target), 27));
            weaver.know(fixture.situation(stock, List.of()), crew);
            WeaveDelta delta = weaver.grow(false);
            plan.apply(delta);
            return delta;
        }

        void pickUp(Node consumer, Crew.Hand worker) {
            UUID fetch = plan.before(consumer.id()).stream()
                .filter(id -> plan.vertices().get(id).node() instanceof TransferNode).findFirst().orElseThrow();
            Item item = consumer == stone ? Items.COBBLESTONE : Items.OAK_PLANKS;
            packs.computeIfAbsent(worker.id(), key -> new ArrayList<>()).add(new ItemStack(item, 64));
            weaver.heldBy(fetch, worker.id(), true);
            weaver.finished(fetch);
        }

        Expansion bind() throws ReflectiveOperationException {
            // Install just the binding so the test can change it without also withdrawing either consumer.
            var field = Weaver.class.getDeclaredField("bonds");
            field.setAccessible(true);
            var binding = new Expansion(UUID.randomUUID());
            binding.together(Set.of(stone.id(), wood.id()));
            binding.bind((Bonds) field.get(weaver));
            return binding;
        }

        Schedule schedule(Crew crew) {
            return new Tours().schedule(plan, Set.of(), crew, fixture.ways, ++tick, true);
        }

        Crew.Hand assignedTo(Schedule schedule, Node work) {
            UUID worker = schedule.tours().values().stream().filter(tour -> tour.nodes().contains(work.id()))
                .map(Tour::resident).findFirst().orElseThrow();
            return worker.equals(first.id()) ? first : second;
        }

        void complete(Crew crew, Node a, Crew.Hand aWorker, Node b, Crew.Hand bWorker) {
            var schedule = schedule(crew);
            helper.assertTrue(assignedTo(schedule, a).id().equals(aWorker.id())
                    && assignedTo(schedule, b).id().equals(bWorker.id()),
                "the schedule gives both steps to residents who can finish them");
            for (Node work : List.of(a, b)) {
                Crew.Hand worker = work == a ? aWorker : bWorker;
                boolean mustFetch = plan.before(work.id()).stream()
                    .anyMatch(id -> plan.vertices().get(id).node() instanceof TransferNode);
                if (mustFetch) {
                    pickUp(work, worker);
                }
                weaver.finished(work.id());
            }
            grow(crew);
            helper.assertTrue(!plan.vertices().containsKey(a.id()) && !plan.vertices().containsKey(b.id()),
                "both consumers leave the plan after their workers report completion");
        }

        @Override
        public void close() {
            weaver.closed();
        }
    }

    private static Licence licence(Vocation trade, Keenness keenness) throws ReflectiveOperationException {
        var constructor = Licence.class.getDeclaredConstructor(Vocation.class, Keenness.class);
        constructor.setAccessible(true);
        return constructor.newInstance(trade, keenness);
    }

    // The same resident where it stands, with a pack of `cells` and only these licences, holding nothing.
    private static Crew.Hand hand(Crew.Hand base, int cells, List<Licence> licences) {
        return new Crew.Hand(base.who(), base.at(), cells, cells, 1, licences, List.of(), Optional.empty());
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
            var building = Vocations.required(ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "building"));
            var haul = licence(hauling, Keenness.FIRST);
            var build = licence(building, Keenness.FIRST);
            var one = f.crew.hands().getFirst();
            var two = g.crew.hands().getFirst();
            builder = two.id();
            var builds = NodeSpec.of(UUID.randomUUID(), ResourceLocation.withDefaultNamespace("build"),
                new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1)).vocation(building)
                .needs(new Need(Goods.specOf(new ItemStack(Items.COBBLESTONE)), 128, true)).done();
            wall = Nodes.done(builds);
            var hauler = hand(one, 6, List.of(haul));
            roomy = new Crew(List.of(hauler, hand(two, 6, List.of(haul, build))));
            cramped = new Crew(List.of(hauler, hand(two, 1, List.of(haul, build))));
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
