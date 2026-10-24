package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
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
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
}
