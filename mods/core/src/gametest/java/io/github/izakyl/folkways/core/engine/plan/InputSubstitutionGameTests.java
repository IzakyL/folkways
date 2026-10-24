package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.InputAllocation;
import io.github.izakyl.folkways.core.api.work.InputSubstitutions;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class InputSubstitutionGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "inputs");
    private static final ItemSpec TARGET = ItemSpec.of(Items.EMERALD);
    private static final ItemSpec PAYMENT = ItemSpec.of(Items.AMETHYST_SHARD);

    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(InputSubstitutionGameTests.class); }

    private static void rule(int cost, Runnable test) {
        InputSubstitutions.replace(OWNER, List.of(new InputSubstitutions.Rule(OWNER, TARGET, PAYMENT, TARGET, cost)));
        try { test.run(); } finally { InputSubstitutions.replace(OWNER, List.of()); }
    }

    private static Node node(PlanningFixture f, List<Need> needs, Function<Worker, Outcome> commit) {
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), Workload.Once.of(1)).needs(needs).done();
        return new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return commit.apply(who); }
        };
    }

    @GameTest(template = "empty")
    public static void mixedStockPaysOriginalsFirstAndRecordsActualUnits(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.EMERALD, 2));
            f.worker.pack().setItem(1, new ItemStack(Items.AMETHYST_SHARD, 6));
            Node node = node(f, List.of(new Need(TARGET, 4).replacing()), who -> {
                helper.assertTrue(who.supplied().stream().allMatch(stack -> stack.is(Items.EMERALD)), "work sees only its requested input");
                return Outcome.done();
            });
            var done = (Outcome.Done) Commit.run(helper.getLevel(), node, f.worker);
            helper.assertValueEqual(Goods.countIn(f.worker.pack(), PAYMENT), 2L, "two original plus four substitute items paid");
            helper.assertValueEqual(done.inputs().stream().mapToInt(input -> input.paid().getCount()).sum(), 6, "receipt records six real items");
            helper.assertValueEqual(done.inputs().stream().mapToInt(input -> input.requested().getCount()).sum(), 4, "receipt covers four inputs");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialUseReturnsRealSubstitutesAndWholeRatios(GameTestHelper helper) {
        rule(3, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 7));
            Node node = node(f, List.of(new Need(TARGET, 2).replacing()), who ->
                new Outcome.Done(List.of(), List.of(), Optional.empty(), List.of(new ItemStack(Items.EMERALD))));
            var done = (Outcome.Done) Commit.run(helper.getLevel(), node, f.worker);
            helper.assertValueEqual(Goods.countIn(f.worker.pack(), PAYMENT), 4L, "one unused unit returns three real shards");
            helper.assertTrue(Goods.countIn(f.worker.pack(), TARGET) == 0 && done.inputs().getFirst().paid().getCount() == 3,
                "no virtual emerald can escape the commit");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shortagesFailuresAndExceptionsRestorePayments(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 4));
            Node shortage = node(f, List.of(new Need(TARGET, 2).replacing(), new Need(ItemSpec.of(Items.GOLD_INGOT), 1)),
                who -> { throw new AssertionError("short work cannot commit"); });
            helper.assertTrue(Commit.run(helper.getLevel(), shortage, f.worker) instanceof Outcome.Failed, "later shortage aborts draw");
            Node failure = node(f, List.of(new Need(TARGET, 2).replacing()), who -> Outcome.failed(LaborRefusal.GOODS_GONE));
            helper.assertTrue(Commit.run(helper.getLevel(), failure, f.worker) instanceof Outcome.Failed, "failed work restores payment");
            Node broken = node(f, List.of(new Need(TARGET, 2).replacing()), who -> { throw new IllegalStateException("fixture"); });
            try { Commit.run(helper.getLevel(), broken, f.worker); throw new AssertionError("exception expected"); }
            catch (IllegalStateException expected) { helper.assertTrue(expected.getMessage().equals("fixture"), "exception propagates"); }
            helper.assertValueEqual(Goods.countIn(f.worker.pack(), PAYMENT), 4L, "every path returns the four original shards");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void substitutionsAreOptInAndCannotInventComponents(GameTestHelper helper) {
        rule(1, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 4));
            ItemStack named = new ItemStack(Items.EMERALD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("identity"));
            for (Need need : List.of(new Need(TARGET, 1), new Need(ItemSpec.exact(named), 1).replacing())) {
                helper.assertTrue(Commit.run(helper.getLevel(), node(f, List.of(need), who -> Outcome.done()), f.worker)
                    instanceof Outcome.Failed, "neither deliveries nor component identities accept category payments");
            }
            helper.assertTrue(!Goods.matches(new ItemStack(Items.AMETHYST_SHARD), TARGET), "goods identity remains physical");
        });
        var f = new PlanningFixture(helper);
        f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD));
        helper.assertTrue(Commit.run(helper.getLevel(), node(f, List.of(new Need(TARGET, 1).replacing()), who -> Outcome.done()), f.worker)
            instanceof Outcome.Failed, "without a provider, base behavior remains unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sharedPaymentsAreReservedOnlyOnceAndReleasedOnCancel(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            Stash source = new Stash(f.source);
            var stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.AMETHYST_SHARD, 6))), Map.of(source, 4));
            Claims claims = new Claims();
            Weaver planner = new Weaver(claims, new Cooldowns());
            planner.know(f.situation(stock, List.of()), f.crew);
            Node first = node(f, List.of(new Need(TARGET, 2).replacing()), who -> Outcome.done());
            Node second = node(f, List.of(new Need(TARGET, 2).replacing()), who -> Outcome.done());
            planner.submit(OWNER, Grown.of(first));
            planner.submit(OWNER, Grown.of(second));
            var view = Shown.view(planner.grow(false));
            long reserved = claims.taken(new Resource.Lot(source, Goods.specOf(new ItemStack(Items.AMETHYST_SHARD))));
            helper.assertTrue(reserved == 4 && view.vertices().containsKey(first.id()) != view.vertices().containsKey(second.id()),
                "six physical shards cannot pay two four-shard jobs: " + reserved);
            planner.withdraw(OWNER, first.id());
            planner.withdraw(OWNER, second.id());
            helper.assertValueEqual(claims.taken(new Resource.Lot(source, Goods.specOf(new ItemStack(Items.AMETHYST_SHARD)))), 0L,
                "cancel releases every physical reservation");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void ratiosCombinePackStacksWithoutDoubleCountingInputs(GameTestHelper helper) {
        rule(3, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 2));
            f.worker.pack().setItem(1, new ItemStack(Items.AMETHYST_SHARD));
            Need need = new Need(TARGET, 1).replacing();
            helper.assertTrue(InputAllocation.canSupply(f.worker.pack(), List.of(need)), "one ratio may span identical stacks");
            helper.assertTrue(!InputAllocation.canSupply(f.worker.pack(), List.of(need, need)), "a shared substitute is counted once");
            var hand = f.crew.hands().getFirst();
            Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, 1, hand.licences(),
                List.of(), Optional.empty(), List.of(new ItemStack(Items.AMETHYST_SHARD, 2), new ItemStack(Items.AMETHYST_SHARD)))));
            var planner = new Weaver(new Claims(), new Cooldowns());
            Node node = node(f, List.of(need), who -> Outcome.done());
            planner.know(f.situation(new Stock(List.of(), Map.of()), List.of()), carrying);
            planner.submit(OWNER, Grown.of(node));
            helper.assertTrue(Shown.view(planner.grow(false)).vertices().containsKey(node.id()), "planning combines the same physical identity");
            helper.assertTrue(Commit.run(helper.getLevel(), node, f.worker) instanceof Outcome.Done && f.worker.pack().isEmpty(),
                "execution consumes those same three shards");
            planner.closed();
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadReplansUntouchedInputsAndInvalidatesOldPayment(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 4));
            Node node = node(f, List.of(new Need(TARGET, 2).replacing()), who -> Outcome.done());
            Stash source = new Stash(f.source);
            var claims = new Claims();
            var planner = new Weaver(claims, new Cooldowns());
            var situation = f.situation(new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.AMETHYST_SHARD, 4))),
                Map.of(source, 4)), List.of());
            planner.know(situation, f.crew);
            planner.submit(OWNER, Grown.of(node));
            helper.assertTrue(Shown.view(planner.grow(false)).vertices().containsKey(node.id()), "the old rule initially feeds the work");
            InputSubstitutions.replace(OWNER, List.of());
            helper.assertTrue(Commit.run(helper.getLevel(), node, f.worker) instanceof Outcome.Failed, "a removed rule cannot be spent");
            helper.assertValueEqual(Goods.countIn(f.worker.pack(), PAYMENT), 4L, "reload leaves the real goods intact");
            planner.know(situation, f.crew);
            helper.assertTrue(!Shown.view(planner.grow(false)).vertices().containsKey(node.id()), "untouched work is replanned after rule removal");
            helper.assertValueEqual(claims.taken(new Resource.Lot(source, Goods.specOf(new ItemStack(Items.AMETHYST_SHARD)))), 0L,
                "reload releases old physical reservations");
            planner.closed();
        });
        helper.succeed();
    }
    @GameTest(template = "empty")
    public static void shortOutputSlotLeavesItsRemainderAndRestoresEarlierInputs(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            var output = new SimpleContainer(new ItemStack(Items.AMETHYST_SHARD)) {
                public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
            };
            f.worker.pack().setItem(0, new ItemStack(Items.GOLD_INGOT));
            Worker worker = PlanningFixture.placed(f.worker, Placement.NOWHERE, List.of(new Worker.Store(f.source, output)));
            Node work = node(f, List.of(new Need(ItemSpec.of(Items.GOLD_INGOT), 1), new Need(TARGET, 1).replacing()),
                who -> { throw new AssertionError("a short output cannot fund work"); });
            helper.assertTrue(Commit.run(helper.getLevel(), work, worker) instanceof Outcome.Failed,
                "an output below one payment unit fails without attempting reinsertion");
            helper.assertTrue(output.countItem(Items.AMETHYST_SHARD) == 1 && f.worker.pack().countItem(Items.GOLD_INGOT) == 1,
                "the remainder stays in its output slot and earlier materials are restored");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shortenedOutputDrawReturnsRealItemsToThePackOrSpillsThem(GameTestHelper helper) {
        rule(2, () -> {
            for (boolean full : List.of(false, true)) {
                var f = new PlanningFixture(helper);
                var output = new SimpleContainer(new ItemStack(Items.AMETHYST_SHARD, 2)) {
                    public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
                    public ItemStack removeItem(int slot, int amount) { return super.removeItem(slot, Math.min(1, amount)); }
                };
                f.worker.pack().setItem(0, new ItemStack(Items.GOLD_INGOT));
                if (full) for (int slot = 1; slot < f.worker.pack().getContainerSize(); slot++)
                    f.worker.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
                List<ItemStack> spills = new ArrayList<>();
                Worker worker = PlanningFixture.placed(f.worker, Placement.NOWHERE, List.of(new Worker.Store(f.source, output)),
                    stack -> spills.add(stack.copy()));
                Node work = node(f, List.of(new Need(ItemSpec.of(Items.GOLD_INGOT), 1), new Need(TARGET, 1).replacing()),
                    who -> { throw new AssertionError("a source that changed during removal cannot commit"); });
                helper.assertTrue(Commit.run(helper.getLevel(), work, worker) instanceof Outcome.Failed,
                    "a partial payment aborts the entire draw");
                helper.assertTrue(output.countItem(Items.AMETHYST_SHARD) == 1 && f.worker.pack().countItem(Items.GOLD_INGOT) == 1,
                    "the source and the earlier material retain their actual items");
                int spilled = spills.stream().filter(stack -> stack.is(Items.AMETHYST_SHARD)).mapToInt(ItemStack::getCount).sum();
                helper.assertTrue(f.worker.pack().countItem(Items.AMETHYST_SHARD) + spilled == 1 && spilled == (full ? 1 : 0),
                    "an output that forbids returns falls back to the pack, then a real dropped payment");
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void extractionExceptionsRestoreEarlierSlotsAndEarlierNeeds(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            var output = new SimpleContainer(new ItemStack(Items.AMETHYST_SHARD), new ItemStack(Items.AMETHYST_SHARD)) {
                public boolean canPlaceItem(int slot, ItemStack stack) { return false; }
                public ItemStack removeItem(int slot, int amount) {
                    if (slot == 1) throw new IllegalStateException("output failed");
                    return super.removeItem(slot, amount);
                }
            };
            f.worker.pack().setItem(0, new ItemStack(Items.GOLD_INGOT));
            Worker worker = PlanningFixture.placed(f.worker, Placement.NOWHERE, List.of(new Worker.Store(f.source, output)));
            Node work = node(f, List.of(new Need(ItemSpec.of(Items.GOLD_INGOT), 1), new Need(TARGET, 1).replacing()),
                who -> { throw new AssertionError("a broken draw cannot commit"); });
            try { Commit.run(helper.getLevel(), work, worker); throw new AssertionError("exception expected"); }
            catch (IllegalStateException expected) { helper.assertTrue(expected.getMessage().equals("output failed"), "the source failure propagates"); }
            helper.assertTrue(output.countItem(Items.AMETHYST_SHARD) == 1 && f.worker.pack().countItem(Items.AMETHYST_SHARD) == 1
                    && f.worker.pack().countItem(Items.GOLD_INGOT) == 1,
                "every successful removal was recorded before the later slot threw");
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void shortNamedIdentityCannotHideACompletePaymentIdentity(GameTestHelper helper) {
        rule(3, () -> {
            var f = new PlanningFixture(helper);
            ItemStack named = new ItemStack(Items.AMETHYST_SHARD);
            named.set(DataComponents.CUSTOM_NAME, Component.literal("short payment"));
            f.worker.pack().setItem(0, named.copy());
            f.worker.pack().setItem(1, new ItemStack(Items.AMETHYST_SHARD, 3));
            Need need = new Need(TARGET, 1).replacing();
            helper.assertTrue(InputAllocation.canSupply(f.worker.pack(), List.of(need)), "the complete plain identity remains reachable");
            Node work = node(f, List.of(need), who -> Outcome.done());
            var result = Commit.run(helper.getLevel(), work, f.worker);
            helper.assertTrue(result instanceof Outcome.Done && Goods.countIn(f.worker.pack(), Goods.specOf(named)) == 1,
                "unplanned execution spends three plain shards and preserves the named shard");
            f.worker.pack().setItem(1, new ItemStack(Items.AMETHYST_SHARD, 3));
            var hand = f.crew.hands().getFirst();
            Crew crew = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, 1, hand.licences(), List.of(),
                Optional.empty(), List.of(named.copy(), new ItemStack(Items.AMETHYST_SHARD, 3)))));
            var planner = new Weaver(new Claims(), new Cooldowns());
            try {
                planner.know(f.situation(new Stock(List.of(), Map.of()), List.of()), crew);
                planner.submit(OWNER, Grown.of(work));
                var vertex = Shown.view(planner.grow(false)).vertices().get(work.id());
                helper.assertTrue(vertex != null && vertex.plan().draws().stream().allMatch(draw ->
                    Goods.matches(new ItemStack(Items.AMETHYST_SHARD), draw.spec())), "planning reserves the complete plain identity");
                Worker worker = PlanningFixture.placed(f.worker, vertex.plan(), List.of());
                helper.assertTrue(InputAllocation.canSupply(worker.pack(), work.spec().needs())
                    && Commit.run(helper.getLevel(), work, worker) instanceof Outcome.Done,
                    "the work-site check and planned draw agree with that reservation");
                helper.assertTrue(Goods.countIn(f.worker.pack(), Goods.specOf(named)) == 1,
                    "the undersized identity was never spent");
            } finally { planner.closed(); }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void removedRuleCannotBorrowAnotherJobsConcreteReservation(GameTestHelper helper) {
        rule(1, () -> {
            var f = new PlanningFixture(helper);
            Stash source = new Stash(f.source);
            helper.getLevel().setBlock(f.source.cell(), Blocks.BARREL.defaultBlockState(), 3);
            var output = Containers.at(helper.getLevel(), f.source.cell()).orElseThrow();
            output.setItem(0, new ItemStack(Items.AMETHYST_SHARD, 2));
            var claims = new Claims();
            var planner = new Weaver(claims, new Cooldowns());
            Node work = node(f, List.of(new Need(TARGET, 1).replacing()), who -> Outcome.done());
            try {
                planner.know(f.situation(new Stock(List.of(new Stock.Holding(source, output.getItem(0))), Map.of(source, 1)), List.of()), f.crew);
                planner.submit(OWNER, Grown.of(work));
                var view = Shown.view(planner.grow(false));
                var fetch = view.vertices().values().stream().filter(vertex -> vertex.node() instanceof TransferNode).findFirst().orElseThrow();
                helper.assertTrue(Commit.run(helper.getLevel(), fetch.node(), PlanningFixture.placed(f.worker, fetch.plan(),
                    List.of(new Worker.Store(f.source, output)))) instanceof Outcome.Done, "the first job actually hauls its payment");
                planner.heldBy(fetch.id(), f.worker.resident().id(), true);
                planner.finished(fetch.id());
                InputSubstitutions.replace(OWNER, List.of());
                output.setItem(1, new ItemStack(Items.EMERALD));
                var other = new PlanningFixture(helper);
                var hand = f.crew.hands().getFirst();
                var second = other.crew.hands().getFirst();
                Crew crew = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 5, 6, 1, hand.licences(), List.of(),
                    Optional.of(work.id()), List.of(new ItemStack(Items.AMETHYST_SHARD))), second));
                planner.know(f.situation(new Stock(List.of(new Stock.Holding(source, output.getItem(0)),
                    new Stock.Holding(source, output.getItem(1))), Map.of(source, 0)), List.of()), crew);
                Node another = node(f, List.of(new Need(TARGET, 1)), who -> Outcome.done());
                planner.submit(OWNER, Grown.of(another));
                view.apply(planner.grow(false));
                helper.assertValueEqual(claims.taken(new Resource.Lot(source, Goods.specOf(new ItemStack(Items.EMERALD)))), 1L,
                    "the concrete emerald belongs to the second job");
                var placement = view.vertices().get(work.id()).plan();
                helper.assertTrue(placement.draws().stream().allMatch(draw -> draw.input().filter(input ->
                    input.requested().equals(TARGET) && input.cost() == 1).isPresent()), "hauling preserves the original request and ratio");
                Worker worker = PlanningFixture.placed(f.worker, placement, List.of(new Worker.Store(f.source, output)));
                helper.assertTrue(Commit.run(helper.getLevel(), work, worker) instanceof Outcome.Failed,
                    "a pinned job with an invalid payment reports missing goods");
                helper.assertTrue(output.countItem(Items.EMERALD) == 1 && f.worker.pack().countItem(Items.AMETHYST_SHARD) == 1,
                    "neither the other job's emerald nor the original payment is taken");
            } finally { planner.closed(); }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void invalidLogicalInputOrRatioCannotReassignReservations(GameTestHelper helper) {
        rule(2, () -> {
            var f = new PlanningFixture(helper);
            f.worker.pack().setItem(0, new ItemStack(Items.AMETHYST_SHARD, 4));
            Node work = node(f, List.of(new Need(TARGET, 1).replacing()), who -> Outcome.done());
            for (Placement.Input input : List.of(new Placement.Input(ItemSpec.of(Items.DIAMOND), 2), new Placement.Input(TARGET, 1))) {
                Placement placement = new Placement(Set.of(), List.of(), List.of(), List.of(), List.of(new Placement.Draw(
                    Optional.empty(), Goods.specOf(new ItemStack(Items.AMETHYST_SHARD)), 4, Optional.empty(), Optional.empty(), Optional.of(input))));
                helper.assertTrue(Commit.run(helper.getLevel(), work, PlanningFixture.placed(f.worker, placement, List.of())) instanceof Outcome.Failed,
                    "the same physical item cannot cross a logical-input or ratio boundary");
                helper.assertTrue(f.worker.pack().countItem(Items.AMETHYST_SHARD) == 4, "invalid allocations leave actual goods intact");
            }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadBetweenNeedsRetriesTheWholeRoundBeforePublishing(GameTestHelper helper) {
        rule(1, () -> {
            var f = new PlanningFixture(helper);
            Stash source = new Stash(f.source);
            var claims = new Claims();
            var planner = new Weaver(claims, new Cooldowns());
            var reached = new CountDownLatch(1);
            var resumed = new CountDownLatch(1);
            var paused = new AtomicBoolean();
            var result = new AtomicReference<WeaveDelta>();
            var failure = new AtomicReference<Throwable>();
            ItemSpec identity = Goods.specOf(new ItemStack(Items.AMETHYST_SHARD));
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target), Stances.at(f.target),
                Workload.Once.of(1)).needs(new Need(TARGET, 1).replacing(), new Need(TARGET, 1).replacing()).done();
            Node work = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
                public boolean acceptsInput(Stash from) {
                    if (claims.taken(new Resource.Lot(source, identity)) == 1 && paused.compareAndSet(false, true)) {
                        reached.countDown();
                        await(resumed);
                    }
                    return true;
                }
            };
            planner.know(f.situation(new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.AMETHYST_SHARD, 4))),
                Map.of(source, 4)), List.of()), f.crew);
            planner.submit(OWNER, Grown.of(work));
            InputSubstitutions.Snapshot captured = InputSubstitutions.snapshot();
            Thread background = new Thread(() -> {
                try { result.set(planner.grow(false)); } catch (Throwable broken) { failure.set(broken); }
            }, "folkways-test-substitution-reload");
            background.start();
            try {
                await(reached);
                InputSubstitutions.replace(OWNER, List.of(new InputSubstitutions.Rule(OWNER, TARGET, PAYMENT, TARGET, 2)));
                helper.assertTrue(captured.choices(new Need(TARGET, 1).replacing()).getFirst().cost() == 1,
                    "the captured round retains its old immutable rules");
            } finally { resumed.countDown(); join(background); }
            try {
                if (failure.get() != null) throw new AssertionError("background planning failed", failure.get());
                var vertex = Shown.view(result.get()).vertices().get(work.id());
                helper.assertTrue(vertex != null && vertex.plan().draws().stream().allMatch(draw ->
                    draw.input().filter(input -> input.cost() == 2).isPresent()), "only the new coherent allocation reaches publication");
                helper.assertValueEqual(claims.taken(new Resource.Lot(source, identity)), 4L,
                    "both one-item needs use the new two-item cost; the stale two-item budget was released");
            } finally { planner.closed(); }
        });
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadAfterKnowCannotPublishAnUnderfundedOldRound(GameTestHelper helper) {
        rule(1, () -> {
            var f = new PlanningFixture(helper);
            Stash source = new Stash(f.source);
            var claims = new Claims();
            var planner = new Weaver(claims, new Cooldowns());
            Node work = node(f, List.of(new Need(TARGET, 1).replacing(), new Need(TARGET, 1).replacing()), who -> Outcome.done());
            try {
                planner.know(f.situation(new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.AMETHYST_SHARD, 3))),
                    Map.of(source, 4)), List.of()), f.crew);
                planner.submit(OWNER, Grown.of(work));
                InputSubstitutions.replace(OWNER, List.of(new InputSubstitutions.Rule(OWNER, TARGET, PAYMENT, TARGET, 2)));
                helper.assertTrue(!Shown.view(planner.grow(false)).vertices().containsKey(work.id()),
                    "three payments cannot publish two needs that now cost four");
                helper.assertValueEqual(claims.taken(new Resource.Lot(source, Goods.specOf(new ItemStack(Items.AMETHYST_SHARD)))), 0L,
                    "neither the discarded round nor its failed retry retains reservations");
            } finally { planner.closed(); }
        });
        helper.succeed();
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("planning synchronization timed out"); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
    }

    private static void join(Thread thread) {
        try { thread.join(10_000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
        if (thread.isAlive()) throw new AssertionError("background planning did not finish");
    }

}
