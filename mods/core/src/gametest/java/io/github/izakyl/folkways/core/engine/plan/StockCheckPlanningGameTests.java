package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.StockCheck;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.ToolUse;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.labor.LaborRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StockCheckPlanningGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "stock_check");
    private static final ItemSpec BREAD = Goods.specOf(Items.BREAD.getDefaultInstance());
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(StockCheckPlanningGameTests.class); }

    private record Action(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) {
            return new Outcome.Done(List.of(), spec.gives().stream().map(amount ->
                new ItemStack(Items.BREAD, (int) amount.count())).toList(), Optional.empty());
        }
    }
    private static class Check implements Node, StockCheck {
        private final NodeSpec spec;
        private final BooleanSupplier present;
        List<ItemStack> drawn = List.of();
        Outcome result = Outcome.done();
        int restored;
        Check(NodeSpec spec, BooleanSupplier present) { this.spec = spec; this.present = present; }
        public NodeSpec spec() { return spec; }
        public Drawn draw(ServerLevel level, Worker who) {
            return present.getAsBoolean() ? new Taken(drawn) : new Short(LaborRefusal.GOODS_GONE);
        }
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) { restored++; }
        public Outcome commit(ServerLevel level, Worker who) {
            return present.getAsBoolean() ? result : Outcome.failed(LaborRefusal.GOODS_GONE);
        }
    }
    private static NodeSpec.Builder spec(WorldPos at, long count) {
        return NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(at), Stances.at(at), Workload.Once.of(1))
            .needs(new Need(BREAD, count));
    }
    private static Check check(WorldPos at, long count) { return new Check(spec(at, count).done(), () -> true); }
    private static Stock stocked(PlanningFixture f, long count) {
        return new Stock(List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.BREAD, (int) count))),
            Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
    }
    private static WeaveView grow(Weaver weaver, Situation world, Crew crew, Node... nodes) {
        weaver.know(world, crew);
        for (Node node : nodes) weaver.submit(OWNER, Grown.of(node));
        return Shown.view(weaver.grow(false));
    }
    private static void observational(GameTestHelper helper, Vertex vertex) {
        helper.assertTrue(vertex.plan().from().isEmpty() && vertex.plan().into().isEmpty()
            && vertex.plan().carrying().isEmpty(), "the target promises no physical inventory movement");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void distantStockDoesNotRelayWhileOrdinaryNeedsStillDo(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var target = check(f.target, 64);
        var weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var graph = grow(weaver, f.situation(stocked(f, 80), List.of()), f.crew, target);
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(),
                "64 kept in the distant warehouse require only the check, never take/put relay");
            observational(helper, graph.vertex(target.id()));
            helper.assertValueEqual(graph.vertex(target.id()).plan().draws().getFirst().count(), 64L, "known stock is reserved");
            helper.assertTrue(graph.vertex(target.id()).plan().draws().getFirst().from().get().equals(new Stash(f.source)),
                "the reservation stays at its actual warehouse");
        } finally { weaver.closed(); }
        weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var ordinary = new Action(spec(f.target, 64).done());
            var graph = grow(weaver, f.situation(stocked(f, 80), List.of()), f.crew, ordinary);
            helper.assertTrue(graph.size() == 3 && weaver.shortfalls().isEmpty(),
                "ordinary Need(false) still relays stock into the consumer's reachable store");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stockChecksSkipFetchAndCanObserveOtherRealms(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var onlySource = Stands.building().container(new Stash(f.source), Set.of(new Stand(f.source))).done();
        var weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var graph = grow(weaver, new Situation(f.ways, stocked(f, 64), List.of(), onlySource), f.crew, check(f.target, 64));
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(), "no reachable landing store still does not fetch");
        } finally { weaver.closed(); }
        weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var graph = grow(weaver, new Situation(f.ways, stocked(f, 64), List.of(), onlySource), f.crew,
                new Action(spec(f.target, 64).done()));
            helper.assertTrue(graph.size() == 2 && weaver.shortfalls().isEmpty(), "ordinary needs retain the fallback fetch");
        } finally { weaver.closed(); }
        var elsewhere = WorldPos.of(Level.NETHER, f.source.cell());
        var stock = new Stock(List.of(new Stock.Holding(new Stash(elsewhere), new ItemStack(Items.BREAD, 64))),
            Map.of(new Stash(elsewhere), 26));
        weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var target = check(f.target, 64);
            var graph = grow(weaver, new Situation(f.ways, stock, List.of(), Stands.NONE), f.crew, target);
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(),
                "known stock in another realm is an observation, never a cross-realm transport");
            helper.assertTrue(graph.vertex(target.id()).plan().draws().getFirst().from().get().pos().equals(elsewhere),
                "the other realm's inventory address remains intact");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void severalCargoOwnersDoNotBecomeTheChecksWorkers(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var other = new PlanningFixture(helper);
        var one = f.crew.hands().getFirst();
        var two = other.crew.hands().getFirst();
        var crew = new Crew(List.of(
            new Crew.Hand(one.who(), one.at(), 0, 6, 1, one.licences(), List.of(), Optional.of(UUID.randomUUID()),
                List.of(new ItemStack(Items.BREAD, 32))),
            new Crew.Hand(two.who(), WorldPos.of(Level.NETHER, two.at().cell()), 0, 6, 1, two.licences(), List.of(),
                Optional.of(UUID.randomUUID()), List.of(new ItemStack(Items.BREAD, 40)))));
        var claims = new Claims();
        var weaver = new Weaver(claims, new Cooldowns());
        try {
            var target = check(f.target, 64);
            var graph = grow(weaver, f.situation(new Stock(List.of(), Map.of()), List.of()), crew, target);
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(),
                "busy residents' actual cargo can jointly satisfy an inventory target");
            helper.assertTrue(graph.pin(target.id()) == null && graph.group(target.id()).equals(Set.of(target.id())),
                "observing cargo binds neither its owner nor another worker");
            helper.assertTrue(graph.vertex(target.id()).plan().draws().size() == 2
                && graph.vertex(target.id()).plan().draws().stream().allMatch(draw -> draw.resident().isPresent()),
                "each source retains its real cargo owner");
            helper.assertValueEqual(claims.taken(new Resource.Cargo(one.id(), BREAD)), 32L, "first physical cargo claim");
            helper.assertValueEqual(claims.taken(new Resource.Cargo(two.id(), BREAD)), 32L, "second physical cargo claim");
            observational(helper, graph.vertex(target.id()));
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void otherConsumersClaimsRemainExclusive(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var claims = new Claims();
        claims.take(new Claim(new Resource.Lot(new Stash(f.source), BREAD), UUID.randomUUID(), 16));
        var weaver = new Weaver(claims, new Cooldowns());
        try {
            var target = check(f.target, 64);
            var graph = grow(weaver, f.situation(stocked(f, 80), List.of()), f.crew, target);
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(), "the unclaimed 64 cover the reserve");
            helper.assertValueEqual(claims.taken(new Resource.Lot(new Stash(f.source), BREAD)), 80L,
                "the target and real consumer cannot share the same units");
            weaver.submit(OWNER, Grown.of(new Action(spec(f.target, 1).done())));
            weaver.grow(false);
            helper.assertTrue(!weaver.shortfalls().isEmpty(), "a further destructive consumer cannot spend the retained stock");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void knownStockMayBeObservedWithoutPermissionToWithdraw(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var source = new Stash(f.source);
        var stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 80))),
            Map.of(source, 25, new Stash(f.target), 27),
            Map.of(source, List.of(new StoreRule.Access(f.source, false, true))));
        var claims = new Claims();
        claims.take(new Claim(new Resource.Lot(source, BREAD), UUID.randomUUID(), 16));
        var weaver = new Weaver(claims, new Cooldowns());
        try {
            var target = check(f.target, 64);
            var graph = grow(weaver, f.situation(stock, List.of()), f.crew, target);
            helper.assertTrue(graph.size() == 1 && weaver.shortfalls().isEmpty(),
                "the known 64 unclaimed units may stay in a warehouse which forbids withdrawals");
            helper.assertValueEqual(claims.taken(new Resource.Lot(source, BREAD)), 80L,
                "the observation still excludes another consumer's 16 claimed units");
            observational(helper, graph.vertex(target.id()));
        } finally { weaver.closed(); }
        weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            grow(weaver, f.situation(stock, List.of()), f.crew, new Action(spec(f.target, 1).done()));
            helper.assertTrue(!weaver.shortfalls().isEmpty(), "ordinary Need still cannot withdraw even one unit");
        } finally { weaver.closed(); }
        weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var filtered = new Check(spec(f.target, 64).done(), () -> true) {
                public boolean acceptsInput(Stash from) { return !from.equals(source); }
            };
            grow(weaver, f.situation(stock, List.of()), f.crew, filtered);
            helper.assertTrue(!weaver.shortfalls().isEmpty(), "the observer's explicit inventory scope remains authoritative");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    private static Workshop bakery(WorldPos at) {
        return bakery(at, Workload.Once.of(1));
    }

    private static Workshop bakery(WorldPos at, Workload workload) {
        return new Workshop() {
            public ResourceLocation id() { return OWNER; }
            public UUID key() { return UUID.nameUUIDFromBytes(("stock-bakery/" + at).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            public WorkSite site() { return new WorkSite.AtBlock(at); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                if (!(intent instanceof Produce produce) || !produce.wanted().admits(BREAD)) return List.of();
                var made = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, site(), Stances.at(at), workload)
                    .gives(new Amount(BREAD, produce.count())).done());
                return List.of(Refinement.Change.of(Grown.of(made)));
            }
        };
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workingLegacyOutputsCannotAlsoSatisfyAStockObservation(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        for (boolean working : List.of(true, false)) {
            var claims = new Claims();
            var weaver = new Weaver(claims, new Cooldowns());
            try {
                Workload.Within pieces = (level, who) -> Workload.Within.WAIT;
                var first = grow(weaver, f.situation(new Stock(List.of(), Map.of()),
                    List.of(bakery(f.source, pieces))), f.crew, new Action(spec(f.target, 4).done()));
                helper.assertTrue(weaver.shortfalls().isEmpty(), "the ordinary consumer has a real legacy maker");
                var maker = first.vertices().values().stream().filter(vertex -> !vertex.node().spec().gives().isEmpty())
                    .findFirst().orElseThrow();
                helper.assertValueEqual(claims.taken(new Resource.Handed(maker.id(), BREAD)), 4L,
                    "the consumer still owns Handed output before the parent's final hand-off");
                var hand = f.crew.hands().getFirst();
                // A pin alone is only a future assignment. An actual start additionally establishes that
                // legacy Within output in this worker's pack aliases its outstanding Handed promises.
                weaver.heldBy(maker.id(), hand.id(), !working);
                var carrying = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 5, 6, hand.pace(), hand.licences(),
                    hand.tools(), working ? Optional.of(maker.id()) : Optional.empty(), List.of(new ItemStack(Items.BREAD, 4)))));
                weaver.know(f.situation(new Stock(List.of(), Map.of()), List.of()), carrying);
                var target = check(f.target, 4);
                weaver.submit(OWNER, Grown.of(target));
                var update = Shown.view(weaver.grow(false));
                if (working) {
                    helper.assertTrue(!weaver.shortfalls().isEmpty() && !update.has(target.id()),
                        "already-produced legacy cargo promised to another consumer cannot cover the stock target again");
                    helper.assertValueEqual(claims.taken(new Resource.Cargo(hand.id(), BREAD)), 0L,
                        "the failed observation does not duplicate the existing Handed claim as Cargo");
                } else {
                    helper.assertTrue(weaver.shortfalls().isEmpty() && update.has(target.id()),
                        "an unstarted future promise does not claim unrelated physical cargo");
                    helper.assertValueEqual(claims.taken(new Resource.Cargo(hand.id(), BREAD)), 4L,
                        "unrelated actual cargo remains available until the promised work really starts");
                }
            } finally { weaver.closed(); }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void observationsDoNotInventFreeStorageSpace(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var stock = new Stock(List.of(new Stock.Holding(new Stash(f.source), new ItemStack(Items.BREAD, 64))),
            Map.of(new Stash(f.source), 0));
        var weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            grow(weaver, f.situation(stock, List.of(bakery(f.source))), f.crew, check(f.target, 64));
            // Another inventory target must really store its product. An ordinary consumer could validly
            // use newly baked bread straight from its maker's pack without needing any warehouse space.
            weaver.submit(OWNER, Grown.of(check(f.target, 1)));
            var graph = Shown.view(weaver.grow(false));
            helper.assertTrue(!weaver.shortfalls().isEmpty(), "keeping a full stack does not make room for one more baked item");
            helper.assertTrue(graph.vertices().values().stream().noneMatch(vertex -> !vertex.node().spec().gives().isEmpty()),
                "a recipe needing imaginary storage space is rolled back");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void producedGoodsMustActuallyBePutAwayBeforeTheCheck(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var inventory = Containers.at(level, f.source.cell()).orElseThrow();
        var sourceOnly = Stands.building().container(new Stash(f.source), Set.of(new Stand(f.source))).done();
        var target = new Check(spec(f.target, 5).done(), () -> Goods.countIn(inventory, BREAD) >= 5);
        var weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var graph = grow(weaver, new Situation(f.ways, new Stock(List.of(), Map.of(new Stash(f.source), 27)),
                List.of(bakery(f.target)), sourceOnly), f.crew, target);
            helper.assertTrue(weaver.shortfalls().isEmpty() && graph.size() == 3,
                "the recipe bootstraps missing stock with maker, real put and check");
            var put = graph.vertices().values().stream().filter(vertex -> vertex.node() instanceof TransferNode).findFirst().orElseThrow();
            var maker = graph.vertices().values().stream().filter(vertex -> !vertex.node().spec().gives().isEmpty()).findFirst().orElseThrow();
            helper.assertTrue(put.plan().into().equals(List.of(new Stash(f.source)))
                && graph.before(target.id()).contains(put.id()) && graph.before(put.id()).contains(maker.id()),
                "the stock observation waits on the actual store deposit");
            observational(helper, graph.vertex(target.id()));
            helper.assertTrue(Commit.run(level, target, f.worker) instanceof Outcome.Failed, "an unmade promise does not satisfy execution");
            var made = (Outcome.Done) Commit.run(level, maker.node(), f.worker);
            made.made().forEach(stack -> Containers.insert(f.worker.pack(), stack));
            helper.assertTrue(Commit.run(level, target, f.worker) instanceof Outcome.Failed,
                "even the actual new pack contents do not replace the required fresh warehouse check");
            helper.assertTrue(Commit.run(level, put.node(), f.worker) instanceof Outcome.Done, "the native put deposits the produced goods");
            helper.assertTrue(Commit.run(level, target, f.worker) instanceof Outcome.Done && f.worker.pack().isEmpty()
                && Goods.countIn(inventory, BREAD) == 5, "the successful check consumes and moves nothing");
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void makersBesideStorageStillPutOutputsIntoStorage(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var weaver = new Weaver(new Claims(), new Cooldowns());
        try {
            var target = check(f.target, 5);
            var graph = grow(weaver, f.situation(new Stock(List.of(), Map.of(new Stash(f.source), 27)),
                List.of(bakery(f.source))), f.crew, target);
            var maker = graph.vertices().values().stream().filter(vertex -> !vertex.node().spec().gives().isEmpty()).findFirst().orElseThrow();
            helper.assertTrue(graph.size() == 2 && maker.plan().into().equals(List.of(new Stash(f.source)))
                && graph.before(target.id()).contains(maker.id()), "adjacent production is put into the real warehouse before checking");
            helper.assertTrue(graph.group(target.id()).equals(Set.of(target.id())), "maker and stock observer never share a pack group");
            observational(helper, graph.vertex(target.id()));
        } finally { weaver.closed(); }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void malformedChecksCannotExchangeOrFabricateMaterial(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var carried = new Check(spec(f.target, 1).needs(new Need(BREAD, 1, true)).done(), () -> true);
        helper.assertTrue(!StockCheck.valid(carried) && Commit.run(helper.getLevel(), carried, f.worker) instanceof Outcome.Failed,
            "carried needs are not inventory observations");
        var gives = new Check(spec(f.target, 1).gives(new Amount(BREAD, 1)).done(), () -> true);
        helper.assertTrue(!StockCheck.valid(gives), "declared goods production is forbidden");
        var leaves = new Check(spec(f.target, 1).leaves(List.of(new Amount(BREAD, 1))).done(), () -> true);
        helper.assertTrue(!StockCheck.valid(leaves), "declared residual goods are forbidden");
        var draw = check(f.target, 1);
        draw.drawn = List.of(new ItemStack(Items.BREAD));
        helper.assertTrue(Commit.run(helper.getLevel(), draw, f.worker) instanceof Outcome.Failed && draw.restored == 1,
            "nonempty staged draws are restored and rejected");
        var unused = check(f.target, 1);
        unused.result = new Outcome.Done(List.of(), List.of(), Optional.empty(), List.of(new ItemStack(Items.BREAD)));
        helper.assertTrue(Commit.run(helper.getLevel(), unused, f.worker) instanceof Outcome.Failed && f.worker.pack().isEmpty(),
            "a fabricated unused list cannot turn an empty check into free material");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void stockChecksCannotEnterPieceOrEquipmentExecution(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Workload.Within pieces = (level, who) -> Workload.Within.LEAVE;
        var within = new Check(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), pieces).needs(new Need(BREAD, 1)).done(), () -> true);
        var continuous = new Check(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), (Workload.Continuous) (level, who) -> true)
            .needs(new Need(BREAD, 1)).done(), () -> true);
        var equipped = new Check(spec(f.target, 1).tools(ToolUse.of(ToolNeed.NONE)).done(), () -> true);
        for (Check invalid : List.of(within, continuous, equipped)) {
            helper.assertTrue(!StockCheck.valid(invalid)
                && Commit.run(helper.getLevel(), invalid, f.worker) instanceof Outcome.Failed,
                "piece work, continuous work and equipment cannot bypass stock-check execution");
            var weaver = new Weaver(new Claims(), new Cooldowns());
            boolean rejected = false;
            try {
                grow(weaver, f.situation(stocked(f, 64), List.of()), f.crew, invalid);
            } catch (IllegalArgumentException expected) {
                rejected = true;
            } finally { weaver.closed(); }
            helper.assertTrue(rejected, "the planner rejects invalid stock-check shapes before laying material flows");
        }
        helper.succeed();
    }
}
