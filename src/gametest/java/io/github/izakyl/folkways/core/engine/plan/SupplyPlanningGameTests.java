package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.core.api.Declaring;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
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
public final class SupplyPlanningGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "supply");
    private static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
    private static final ItemSpec PLANKS = ItemSpec.of(ResourceLocation.withDefaultNamespace("oak_planks"));
    private static final ItemSpec WHEAT = ItemSpec.of(ResourceLocation.withDefaultNamespace("wheat"));
    private static final ItemSpec BALE = ItemSpec.of(ResourceLocation.withDefaultNamespace("hay_block"));
    private static final RefusalKind BUSY = () -> "folkways_test.busy";

    private record Action(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    }
    private record Guarded(NodeSpec spec, Hold hold) implements Intent { }

    private static final class Counted implements Hold {
        private final boolean grants;
        int taken;
        final List<Ending> released = new ArrayList<>();
        Counted(boolean grants) { this.grants = grants; }
        public Optional<RefusalKind> take() {
            taken++;
            return grants ? Optional.empty() : Optional.of(BUSY);
        }
        public void release(Ending how) { released.add(how); }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SupplyPlanningGameTests.class); }
    @SubscribeEvent
    public static void declare(Declaring event) {
        event.refinement(new Refinement() {
            public ResourceLocation id() { return OWNER; }
            public Optional<Change> rewrite(Intent intent, Grown graph) {
                return intent instanceof Guarded guarded
                    ? Optional.of(Change.of(Grown.of(new Action(guarded.spec()))).holding(guarded.hold()))
                    : Optional.empty();
            }
        });
    }

    private static Node consumer(PlanningFixture f, Need... needs) {
        return new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), Workload.Once.of(1)).needs(needs).done());
    }

    private static Known known(PlanningFixture f, List<Stock.Holding> held, List<Workshop> workshops) {
        Stock stock = new Stock(held, Map.of(new Stash(f.source), 27, new Stash(f.target), 27));
        return new Known(f.situation(stock, workshops), f.crew.hands());
    }

    private static Workshop bakery(PlanningFixture f, Hold hold) {
        return new Workshop() {
            public ResourceLocation id() { return OWNER; }
            public UUID key() { return UUID.nameUUIDFromBytes(new byte[] {7}); }
            public WorkSite site() { return new WorkSite.AtBlock(f.source); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                if (!(intent instanceof Produce produce) || !produce.wanted().admits(BREAD)) return List.of();
                Node oven = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, site(), Stances.at(f.source),
                    Workload.Once.of(1)).gives(new Amount(BREAD, produce.count())).done());
                return List.of(Refinement.Change.of(Grown.of(oven)).holding(hold));
            }
        };
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void declaredNeedsAreOpenEdgesIntoTheirConsumer(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Node eats = consumer(f, new Need(BREAD, 4), new Need(PLANKS, 2));
        List<Edge> open = Edge.into(eats, 0, Set.of());
        helper.assertTrue(open.size() == 2
            && open.get(0).id().equals(Edge.idOf(eats.id(), 0)) && open.get(1).id().equals(Edge.idOf(eats.id(), 1))
            && open.stream().allMatch(edge -> edge.consumer().equals(eats.id())),
            "each declared need is one open edge into its consumer, named by its place");
        helper.assertTrue(Grown.of(eats).completions().equals(Set.of(eats.id())),
            "only work is completed; the edges into it are fed, not done");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFedEdgeDoesNotOutliveItsConsumer(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var held = List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.BREAD, 4)));
        var known = known(f, held, List.of());
        Weaver weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            weaver.know(known.world(), f.crew);
            weaver.submit(OWNER, Grown.of(consumer(f, new Need(BREAD, 4))));
            Weave grown = weaver.grow(false);
            helper.assertTrue(grown.vertices().size() == 3 && weaver.shortfalls().isEmpty(),
                "bread out of reach is relayed into the store the consumer reaches: a take, a put and the consumer");
            helper.assertTrue(grown.vertices().values().stream().noneMatch(vertex -> vertex.node() instanceof Intent),
                "no intent reaches the schedule");
            grown.vertices().keySet().forEach(weaver::finished);
            Weave after = weaver.grow(false);
            helper.assertTrue(after.vertices().isEmpty() && after.pending().isEmpty() && !weaver.asking(),
                "once its consumer is done, nothing grown for it keeps the ask alive");
        } finally {
            weaver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void refusedWorkshopHoldAdmitsNothing(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hold = new Counted(false);
        var planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved solved = planner.handle(known(f, List.of(), List.of(bakery(f, hold))),
                List.of(new Message.Submitted(OWNER, Grown.of(consumer(f, new Need(BREAD, 4))))), 0);
            helper.assertTrue(hold.taken == 1 && hold.released.isEmpty(), "a refused hold was never held");
            helper.assertTrue(solved.weave().vertices().isEmpty() && !solved.diagnosis().refused().isEmpty()
                    && !solved.diagnosis().shortfalls().isEmpty(),
                "without its hold the answer lands nothing, and both the refusal and the gap are reported");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workshopHoldIsTakenOnAdmissionAndReleasedWithItsWork(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hold = new Counted(true);
        var known = known(f, List.of(), List.of(bakery(f, hold)));
        Node eats = consumer(f, new Need(BREAD, 4));
        var planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved solved = planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(eats))), 0);
            helper.assertTrue(solved.weave().vertices().size() == 2 && solved.diagnosis().shortfalls().isEmpty(),
                "the bakery's answer is admitted, its bread handed to the consumer by whoever bakes it");
            helper.assertTrue(solved.weave().groups().stream().anyMatch(group -> group.contains(eats.id())),
                "baking and eating ride together, so one worker carries the bread between them");
            helper.assertTrue(hold.taken == 1 && hold.released.isEmpty(), "the hold is taken once, when admitted");
            Solved gone = planner.handle(known, List.of(new Message.Withdrawn(OWNER, eats.id()), Message.ANSWERED), 1);
            helper.assertTrue(gone.weave().vertices().isEmpty()
                    && hold.released.equals(List.of(Ending.REVOKED)),
                "withdrawing the consumer takes its production with it and releases the hold once");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void askedHoldsAreTakenWhenTheAskIsAdmitted(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var known = known(f, List.of(), List.of());
        for (boolean grants : List.of(false, true)) {
            var hold = new Counted(grants);
            var guarded = new Guarded(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.source),
                Stances.WHEREVER, Workload.Once.of(1)).done(), hold);
            var planner = new Planner(new Claims(), new Cooldowns());
            try {
                Solved solved = planner.handle(known, List.of(new Message.Submitted(OWNER, Grown.of(guarded))), 0);
                helper.assertTrue(hold.taken == 1 && solved.weave().vertices().containsKey(guarded.id()) == grants
                        && solved.diagnosis().refused().isEmpty() == grants,
                    "an ask is admitted only when the holds its refinement depends on are granted");
                planner.handle(known, List.of(new Message.Withdrawn(OWNER, guarded.id())), 1);
                helper.assertTrue(hold.released.equals(grants ? List.of(Ending.REVOKED) : List.of()),
                    "only a granted hold is released, and only once");
            } finally {
                planner.closed();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void shortfallNamesTheSupplyThatCouldNotBeResolved(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Node eats = consumer(f, new Need(BREAD, 4));
        var planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved solved = planner.handle(known(f, List.of(), List.of()),
                List.of(new Message.Submitted(OWNER, Grown.of(eats))), 0);
            helper.assertTrue(solved.weave().vertices().isEmpty() && solved.diagnosis().shortfalls().size() == 1,
                "a need with no source leaves nothing planned");
            Shortfall gap = solved.diagnosis().shortfalls().getFirst();
            helper.assertTrue(gap.holes().equals(List.of(Edge.idOf(eats.id(), 0)))
                    && gap.consumer().equals(eats.id()) && gap.missing() == 4,
                "the shortfall names the open edge and the consumer it was for");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }

    private static Workshop recipe(PlanningFixture f, int key, ItemSpec makes, long perRun, Need takes, int ticks) {
        return new Workshop() {
            public ResourceLocation id() { return OWNER; }
            public UUID key() { return UUID.nameUUIDFromBytes(new byte[] {(byte) key}); }
            public WorkSite site() { return new WorkSite.AtBlock(f.source); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                if (!(intent instanceof Produce produce) || !Goods.overlap(produce.wanted(), makes)) return List.of();
                long runs = (produce.count() + perRun - 1) / perRun;
                Node station = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, site(), Stances.at(f.source),
                    Workload.Once.of(1)).needs(new Need(takes.spec(), takes.count() * runs))
                    .gives(new Amount(makes, perRun * runs)).estimate((int) (ticks * runs)).done());
                return List.of(Refinement.Change.of(Grown.of(station)));
            }
        };
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aWayBackThroughTheAskedGoodsIsNotCheaperThanMakingThem(GameTestHelper helper) {
        bakesRatherThanUnpacks(helper, BREAD);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aWayBackThroughATagHoldingTheAskedGoodsIsNotCheaperEither(GameTestHelper helper) {
        bakesRatherThanUnpacks(helper, ItemSpec.of(ItemTags.create(ResourceLocation.fromNamespaceAndPath("c", "foods/bread"))));
    }

    private static void bakesRatherThanUnpacks(GameTestHelper helper, ItemSpec baled) {
        var f = new PlanningFixture(helper);
        Node eats = consumer(f, new Need(BREAD, 4));
        var held = List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.WHEAT, 64)));
        var workshops = List.of(
            recipe(f, 11, BREAD, 1, new Need(WHEAT, 3), 200),
            recipe(f, 12, BREAD, 9, new Need(BALE, 1), 20),
            recipe(f, 13, BALE, 1, new Need(baled, 9), 20));
        var planner = new Planner(new Claims(), new Cooldowns());
        try {
            Solved solved = planner.handle(known(f, held, workshops),
                List.of(new Message.Submitted(OWNER, Grown.of(eats))), 0);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(),
                "bread that wheat can make is not short: " + solved.diagnosis().shortfalls());
            helper.assertTrue(solved.weave().vertices().values().stream()
                    .map(vertex -> vertex.node().spec())
                    .anyMatch(spec -> spec.needs().stream().anyMatch(need -> need.spec().equals(WHEAT))),
                "the bread is baked from wheat, not unpacked from a bale that would need the bread first");
            helper.assertTrue(solved.weave().vertices().values().stream()
                    .map(vertex -> vertex.node().spec())
                    .noneMatch(spec -> spec.needs().stream().anyMatch(need -> need.spec().equals(BALE))),
                "no bale is packed only to be unpacked back into the bread it was packed from");
        } finally {
            planner.closed();
        }
        helper.succeed();
    }
}
