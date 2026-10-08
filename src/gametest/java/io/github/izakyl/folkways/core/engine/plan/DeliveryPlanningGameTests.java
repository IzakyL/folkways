package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Before;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.haul.Receipt;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeliveryPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DeliveryPlanningGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void unstackableDeliverySplitsToFitThePack(GameTestHelper helper) {
        deliverSaddles(helper, 6);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void expandedPackCarriesMoreThanTheBaselineInOneTrip(GameTestHelper helper) {
        deliverSaddles(helper, 18);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aBatchIsSizedByTheRoomKnownWhenItIsGrown(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper, 18);
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        List<Stock.Holding> holdings = new java.util.ArrayList<>();
        for (int i = 0; i < 24; i++) {
            holdings.add(new Stock.Holding(new Stash(fixture.source), new ItemStack(Items.SADDLE)));
        }
        var situation = fixture.situation(new Stock(holdings,
            Map.of(new Stash(fixture.source), 3, new Stash(fixture.target), 27)), List.of());
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), saddles, 24);
        List<Grown> goals = task.goals();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        var hand = fixture.crew.hands().getFirst();
        Crew occupied = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 17, 18, 1,
            hand.licences(), hand.tools(), Optional.empty())));
        var shrunk = solver.solve(situation, occupied, goals, List.of(), 0);
        var tour = shrunk.schedule().tours().get(hand.id());
        helper.assertTrue(tour != null && tour.nodes().size() == 2,
            "one pack-sized batch is fetched and delivered");
        var take = shrunk.weave().vertices().get(tour.nodes().getFirst());
        helper.assertTrue(take.plan().carrying().getFirst().count() == 17 && task.remaining() == 24,
            "the batch fits the room known when it was grown, not the total delivery remaining");
        Crew working = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 16, 18, 1,
            hand.licences(), hand.tools(), Optional.of(take.id()))));
        var started = solver.solve(situation, working, goals, List.of(), 2);
        helper.assertTrue(started.weave().vertices().containsKey(take.id()),
            "a node currently held by a worker must not be replaced");
        var fetched = solver.solve(situation, occupied, goals, List.of(new Message.Finished(take.id())), 3);
        helper.assertTrue(fetched.weave().vertices().containsKey(tour.nodes().getLast())
                && fetched.weave().vertices().size() == 1,
            "once goods have been picked up, keep the existing delivery instead of fetching twice");
        solver.closed();
        helper.succeed();
    }

    // Where the schedule last placed the work on its route.
    private static Object placedAt(PlanningFixture.Rounds solver, UUID node) {
        try {
            var tours = Planner.class.getDeclaredField("tours");
            tours.setAccessible(true);
            var settled = Tours.class.getDeclaredField("settled");
            settled.setAccessible(true);
            Object placed = ((Map<?, ?>) settled.get(tours.get(solver.planner()))).get(node);
            var at = placed.getClass().getDeclaredMethod("at");
            at.setAccessible(true);
            return at.invoke(placed);
        } catch (ReflectiveOperationException broken) {
            throw new AssertionError(broken);
        }
    }

    private static void deliverSaddles(GameTestHelper helper, int capacity) {
        PlanningFixture fixture = new PlanningFixture(helper, capacity);
        var level = helper.getLevel();
        level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(fixture.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, fixture.source.cell()).orElseThrow();
        var target = Containers.at(level, fixture.target.cell()).orElseThrow();
        for (int slot = 0; slot < 24; slot++) {
            source.setItem(slot, new ItemStack(Items.SADDLE));
        }
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), saddles, 24);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        List<Message> feedback = List.of();
        int delivered = 0;
        for (int cycle = 0; cycle <= 24 / capacity + 1; cycle++) {
            List<Stock.Holding> holdings = new java.util.ArrayList<>();
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                if (!source.getItem(slot).isEmpty()) {
                    holdings.add(new Stock.Holding(new Stash(fixture.source), source.getItem(slot).copy()));
                }
            }
            Stock stock = new Stock(holdings,
                Map.of(new Stash(fixture.source), 3 + delivered, new Stash(fixture.target), 27 - delivered));
            var solved = solver.solve(fixture.situation(stock, List.of()), fixture.crew,
                task.goals(), feedback, cycle);
            if (task.complete()) {
                helper.assertTrue(delivered == 24 && solved.weave().vertices().isEmpty(),
                    "the finite task must complete after all batches arrive");
                solver.closed();
                helper.succeed();
                return;
            }
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(), "each batch must fit available capacity");
            var tour = solved.schedule().tours().get(fixture.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2, "each batch is one fetch and one delivery");
            int batch = Math.min(capacity, 24 - delivered);
            long peak = 0;
            for (UUID nodeId : tour.nodes()) {
                var node = solved.weave().vertices().get(nodeId).node();
                Outcome outcome = Commit.run(level, node, fixture.worker);
                helper.assertTrue(outcome instanceof Outcome.Done, "the complete batch must execute");
                for (ItemStack made : ((Outcome.Done) outcome).made()) {
                    helper.assertTrue(Containers.insert(fixture.worker.pack(), made).isEmpty(), "the load must fit");
                }
                node.ended(level, Ending.DONE);
                peak = Math.max(peak, Goods.countIn(fixture.worker.pack(), saddles));
            }
            delivered += batch;
            helper.assertTrue(peak == batch && Goods.countIn(target, saddles) == delivered
                    && Goods.countIn(source, saddles) == 24 - delivered,
                "one trip must use the available pack capacity without losing goods");
            feedback = tour.nodes().stream().<Message>map(Message.Finished::new).toList();
        }
        solver.closed();
        throw new AssertionError("delivery did not finish");
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deliveryExpandsAsAnOrdinaryTaskAndDoesNotUseItsOwnStock(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(fixture.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, fixture.source.cell()).orElseThrow();
        var target = Containers.at(level, fixture.target.cell()).orElseThrow();
        source.setItem(0, new ItemStack(Items.BREAD, 8));
        target.setItem(0, new ItemStack(Items.BREAD, 10));
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        Stock stock = new Stock(List.of(
            new Stock.Holding(new Stash(fixture.source), source.getItem(0).copy()),
            new Stock.Holding(new Stash(fixture.target), target.getItem(0).copy())),
            Map.of(new Stash(fixture.source), 26, new Stash(fixture.target), 26));
        Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), bread, 8);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        Situation situation = fixture.situation(stock, List.of());
        Solved solved = solver.solve(situation, fixture.crew, delivery.goals(), List.of(), 0);
        helper.assertTrue(solved.weave().vertices().size() == 2, "delivery must grow a fetch dependency");
        helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(), "the delivery has a source");
        var tour = solved.schedule().tours().get(fixture.worker.resident().id());
        helper.assertTrue(tour != null && tour.nodes().size() == 2, "one worker must fetch then deliver");
        UUID fetchId = tour.nodes().getFirst();
        UUID putId = tour.nodes().getLast();
        helper.assertTrue(solved.weave().links().isEmpty()
                && solved.weave().flows().contains(new Flow(new Flow.From.Lying(new Stash(fixture.source)), fetchId,
                    bread, 8))
                && solved.weave().flows().contains(new Flow(new Flow.From.Work(fetchId), putId, bread, 8))
                && solved.weave().orders().equals(List.of(new Before(fetchId, putId))),
            "the goods' way is material edges alone: out of the source into the fetch, and from it into the put");
        for (UUID nodeId : tour.nodes()) {
            var node = solved.weave().vertices().get(nodeId).node();
            Outcome outcome = Commit.run(level, node, fixture.worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "transfer must succeed");
            for (ItemStack made : ((Outcome.Done) outcome).made()) {
                Containers.insert(fixture.worker.pack(), made);
            }
            node.ended(level, Ending.DONE);
        }
        helper.assertTrue(Goods.countIn(source, bread) == 0 && Goods.countIn(target, bread) == 18,
            "eight new items must be delivered without recycling the destination's ten items");
        Solved finished = solver.solve(situation, fixture.crew, delivery.goals(),
            tour.nodes().stream().<Message>map(Message.Finished::new).toList(), 1);
        helper.assertTrue(delivery.complete() && finished.weave().vertices().isEmpty(),
            "the arrived load settles the delivery, so its requester stops asking");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingSourceDoesNotRecycleDestinationOrLeaveClaims(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.target),
            new ItemStack(Items.BREAD, 10))), Map.of(new Stash(fixture.target), 26));
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), bread, 8);
        Claims claims = new Claims();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(claims, new Cooldowns());
        Solved solved = solver.solve(fixture.situation(stock, List.of()), fixture.crew,
            task.goals(), List.of(), 0);
        helper.assertTrue(solved.weave().vertices().isEmpty(), "an unfulfillable delivery must not run");
        helper.assertTrue(claims.size() == 0 && task.remaining() == 8,
            "discarding a speculative task releases capacity without completing the delivery");
        solver.closed();
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void personalSuppliesBindTheFetchToTheRecipient(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.source),
            new ItemStack(Items.BREAD, 8))), Map.of(new Stash(fixture.source), 26));
        var recipient = fixture.crew.hands().getFirst();
        var stranger = new Resident(UUID.randomUUID(),
            recipient.who().kind());
        Crew crew = new Crew(List.of(new Crew.Hand(stranger, fixture.source, 6, 64, 1,
            recipient.licences(), List.of(), Optional.empty()), recipient));
        Owed task = new Owed(new WorkSite.AtEntity(recipient.id(), fixture.target),
            Stances.WHEREVER, bread, 2);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        var solved = solver.solve(fixture.situation(stock, List.of()), crew,
            task.goals(), List.of(), 0);
        var acquired = solved.schedule().tours().get(recipient.id()).nodes();
        helper.assertTrue(acquired.size() == 2
                && solved.weave().vertices().get(acquired.getFirst()).node() instanceof TransferNode
                && solved.weave().vertices().get(acquired.getLast()).node() instanceof Receipt,
            "personal supplies require one acquisition by the recipient, who then keeps them");
        helper.assertTrue(!solved.schedule().tours().containsKey(stranger.id()),
            "another resident must not pick up this resident's kit");
        helper.assertTrue(placedAt(solver, acquired.getLast()).equals(placedAt(solver, acquired.getFirst())),
            "keeping the goods is done where the recipient took them, not where the recipient was last seen");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aToolPutAwayIsFetchedBackIntoItsOwnersPack(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec hoes = ItemSpec.of(net.minecraft.tags.ItemTags.HOES);
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.source),
            new ItemStack(Items.WOODEN_HOE))), Map.of(new Stash(fixture.source), 26));
        var recipient = fixture.crew.hands().getFirst();
        Owed kit = new Owed(new WorkSite.AtEntity(recipient.id(), fixture.target), Stances.WHEREVER, hoes, 1);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(fixture.situation(stock, List.of()), fixture.crew, kit.goals(), List.of(), 0);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty()
                    && solved.schedule().tours().containsKey(recipient.id()),
                "a tool put away is fetched back by its owner: " + solved.diagnosis().shortfalls()
                    + " " + solved.schedule().unassigned());
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void personalBatchFitsItsRecipientEvenWhenAnotherWorkerHasALargerPack(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        List<Stock.Holding> holdings = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            holdings.add(new Stock.Holding(new Stash(fixture.source), new ItemStack(Items.SADDLE)));
        }
        var recipient = fixture.crew.hands().getFirst();
        var stranger = new Resident(UUID.randomUUID(), recipient.who().kind());
        Crew crew = new Crew(List.of(new Crew.Hand(stranger, fixture.source, 18, 18, 1,
            recipient.licences(), List.of(), Optional.empty()), recipient));
        Owed task = new Owed(new WorkSite.AtEntity(recipient.id(), fixture.target),
            Stances.WHEREVER, saddles, 12);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        var solved = solver.solve(fixture.situation(new Stock(holdings, Map.of(new Stash(fixture.source), 15)),
            List.of()), crew, task.goals(), List.of(), 0);
        var tour = solved.schedule().tours().get(recipient.id());
        helper.assertTrue(tour != null && tour.nodes().size() == 2 && solved.schedule().tours().size() == 1,
            "the recipient must be able to execute the whole batch without another worker");
        long load = solved.weave().vertices().get(tour.nodes().getFirst())
            .plan().carrying().stream().mapToLong(amount -> amount.count()).sum();
        helper.assertTrue(load == 6, "the batch must use the recipient's six cells, not the stranger's eighteen");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void alternativeItemsShareOneCarrierAndCommitTogether(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(fixture.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(fixture.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, fixture.source.cell()).orElseThrow();
        var target = Containers.at(level, fixture.target.cell()).orElseThrow();
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 2));
        source.setItem(1, new ItemStack(Items.GOLD_INGOT, 3));
        ItemSpec goods = ItemSpec.anyOf(List.of(ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot")),
            ItemSpec.of(ResourceLocation.withDefaultNamespace("gold_ingot"))));
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.source), source.getItem(0).copy()),
            new Stock.Holding(new Stash(fixture.source), source.getItem(1).copy())),
            Map.of(new Stash(fixture.source), 25, new Stash(fixture.target), 27));
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), goods, 5);
        var first = fixture.crew.hands().getFirst();
        var other = new Resident(UUID.randomUUID(), first.who().kind());
        Crew crew = new Crew(List.of(first, new Crew.Hand(other, fixture.source, 6, 64, 1,
            first.licences(), List.of(), Optional.empty())));
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        var solved = solver.solve(fixture.situation(stock, List.of()), crew,
            task.goals(), List.of(), 0);
        helper.assertTrue(solved.schedule().tours().size() == 1,
            "all inputs carried into one action must stay with the same worker");
        var tour = solved.schedule().tours().values().iterator().next();
        helper.assertTrue(tour.nodes().size() == 3, "two distinct items require two fetches and one delivery");
        for (UUID nodeId : tour.nodes()) {
            var node = solved.weave().vertices().get(nodeId).node();
            Outcome outcome = Commit.run(level, node, fixture.worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "mixed transfer must succeed");
            for (ItemStack made : ((Outcome.Done) outcome).made()) {
                Containers.insert(fixture.worker.pack(), made);
            }
        }
        helper.assertTrue(Goods.countIn(target, goods) == 5 && Goods.countIn(source, goods) == 0,
            "the full batch must be transferred even when the filter matches different items");
        solver.closed();
        helper.succeed();
    }

}
