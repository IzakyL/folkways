package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.plan.internal.Receipt;
import io.github.izakyl.folkways.core.resident.api.Resident;
import io.github.izakyl.folkways.core.resident.internal.ResidentPack;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.work.internal.HaulWork;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.effect.Stats;
import io.github.izakyl.folkways.core.effect.perk.PerkPool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DeliveryPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DeliveryPlanningGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void unstackableDeliverySplitsToFitThePack(GameTestHelper helper) {
        int capacity = 6;
        PlanningFixture fixture = new PlanningFixture(helper, capacity);
        var barrels = fixture.barrels();
        for (int slot = 0; slot < 24; slot++) {
            barrels.source().setItem(slot, new ItemStack(Items.SADDLE));
        }
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), saddles, 24);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds();
        List<Message> feedback = List.of();
        int delivered = 0;
        for (int cycle = 0; cycle <= 24 / capacity + 1; cycle++) {
            Stock stock = new Stock(holdings(fixture, barrels.source()),
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
            long peak = carry(helper, fixture, solved, tour.nodes(), saddles);
            delivered += batch;
            helper.assertTrue(peak == batch && Goods.countIn(barrels.target(), saddles) == delivered
                    && Goods.countIn(barrels.source(), saddles) == 24 - delivered,
                "one trip must use the available pack capacity without losing goods");
            feedback = tour.nodes().stream().<Message>map(Message.Finished::new).toList();
        }
        solver.closed();
        throw new AssertionError("delivery did not finish");
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aBatchIsSizedByTheRoomKnownWhenItIsGrown(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper, 18);
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        List<Stock.Holding> holdings = new ArrayList<>();
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

    // Commits the tour's work in order with the fixture's worker, as its labor would, and returns the most of
    // `goods` the pack held at once.
    private static long carry(GameTestHelper helper, PlanningFixture fixture, Planned solved, List<UUID> nodes,
                              ItemSpec goods) {
        var level = helper.getLevel();
        long peak = 0;
        for (UUID id : nodes) {
            var node = solved.weave().vertices().get(id).node();
            Outcome outcome = Commit.run(level, node, fixture.worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the planned load executes in the actual pack");
            for (ItemStack made : ((Outcome.Done) outcome).made()) {
                helper.assertTrue(Containers.insert(fixture.worker.pack(), made).isEmpty(), "every returned item fits");
            }
            node.ended(level, Ending.DONE);
            peak = Math.max(peak, Goods.countIn(fixture.worker.pack(), goods));
        }
        return peak;
    }

    private static List<Stock.Holding> holdings(PlanningFixture fixture, Container source) {
        List<Stock.Holding> holdings = new ArrayList<>();
        for (int slot = 0; slot < source.getContainerSize(); slot++) {
            if (!source.getItem(slot).isEmpty()) {
                holdings.add(new Stock.Holding(new Stash(fixture.source), source.getItem(slot).copy()));
            }
        }
        return holdings;
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void deliveryExpandsAsAnOrdinaryTaskAndDoesNotUseItsOwnStock(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        var barrels = fixture.barrels();
        var source = barrels.source();
        var target = barrels.target();
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
        Planned solved = solver.solve(situation, fixture.crew, delivery.goals(), List.of(), 0);
        helper.assertTrue(solved.weave().vertices().size() == 2, "delivery must grow a fetch dependency");
        helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(), "the delivery has a source");
        var tour = solved.schedule().tours().get(fixture.worker.resident().id());
        helper.assertTrue(tour != null && tour.nodes().size() == 2, "one worker must fetch then deliver");
        UUID fetchId = tour.nodes().getFirst();
        UUID putId = tour.nodes().getLast();
        helper.assertTrue(solved.weave().orders().equals(List.of(new Before(fetchId, putId))),
            "the goods' way orders the fetch before the put, and nothing else");
        carry(helper, fixture, solved, tour.nodes(), bread);
        helper.assertTrue(Goods.countIn(source, bread) == 0 && Goods.countIn(target, bread) == 18,
            "eight new items must be delivered without recycling the destination's ten items");
        Planned finished = solver.solve(situation, fixture.crew, delivery.goals(),
            tour.nodes().stream().<Message>map(Message.Finished::new).toList(), 1);
        helper.assertTrue(delivery.complete() && finished.weave().vertices().isEmpty(),
            "the arrived load settles the delivery, so its requester stops asking");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void missingSourceDoesNotRecycleDestinationOrLeaveClaims(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec bread = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
        Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.target),
            new ItemStack(Items.BREAD, 10))), Map.of(new Stash(fixture.target), 26));
        Owed task = new Owed(new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), bread, 8);
        Claims claims = new Claims();
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(claims, new Cooldowns());
        Planned solved = solver.solve(fixture.situation(stock, List.of()), fixture.crew,
            task.goals(), List.of(), 0);
        helper.assertTrue(solved.weave().vertices().isEmpty(), "an unfulfillable delivery must not run");
        helper.assertTrue(claims.size() == 0 && task.remaining() == 8,
            "discarding a speculative task releases capacity without completing the delivery");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
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
        helper.assertTrue(Booked.in(solver.planner(), acquired.getLast()).at()
                .equals(Booked.in(solver.planner(), acquired.getFirst()).at()),
            "keeping the goods is done where the recipient took them, not where the recipient was last seen");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void aToolPutAwayIsFetchedBackIntoItsOwnersPack(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec hoes = ItemSpec.of(ItemTags.HOES);
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

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void personalBatchFitsItsRecipientEvenWhenAnotherWorkerHasALargerPack(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec saddles = ItemSpec.of(ResourceLocation.withDefaultNamespace("saddle"));
        List<Stock.Holding> holdings = new ArrayList<>();
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

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void alternativeItemsShareOneCarrierAndCommitTogether(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        var barrels = fixture.barrels();
        var source = barrels.source();
        var target = barrels.target();
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
        carry(helper, fixture, solved, tour.nodes(), goods);
        helper.assertTrue(Goods.countIn(target, goods) == 5 && Goods.countIn(source, goods) == 0,
            "the full batch must be transferred even when the filter matches different items");
        solver.closed();
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core", timeoutTicks = 100)
    public static void heavyHaulingDeliversMoreThanOneStackButStillFitsItsActualPack(GameTestHelper helper) {
        for (int rank : new int[]{0, 3}) {
            var effects = PerkPool.effects(rank == 0 ? Map.of() : Map.of(HaulWork.HEAVY, rank));
            var pack = new ResidentPack(1);
            pack.applyCapacityMultiplier(effects.value(Stats.PACK_ROOM));
            int cells = pack.getContainerSize();
            helper.assertTrue(cells == (rank == 0 ? 1 : 2), "the real heavy-hauling perk expands this pack's capacity");
            PlanningFixture fixture = new PlanningFixture(helper, cells);
            var barrels = fixture.barrels();
            var source = barrels.source();
            var target = barrels.target();
            source.clearContent();
            target.clearContent();
            for (int slot = 0; slot < 5; slot++) {
                source.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            ItemSpec cobblestone = ItemSpec.of(ResourceLocation.withDefaultNamespace("cobblestone"));
            List<Stock.Holding> holdings = holdings(fixture, source);
            Owed task = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), cobblestone, 320);
            PlanningFixture.Rounds solver = new PlanningFixture.Rounds();
            try {
                var solved = solver.solve(fixture.situation(new Stock(holdings,
                    Map.of(new Stash(fixture.source), 22, new Stash(fixture.target), 27)), List.of()),
                    fixture.crew, task.goals(), List.of(), 0);
                helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(), "the first batch must be satisfiable");
                var tour = solved.schedule().tours().get(fixture.worker.resident().id());
                helper.assertTrue(tour != null && tour.nodes().size() == 2, "one delivery is one fetch and one put");
                long planned = solved.weave().vertices().get(tour.nodes().getFirst()).plan().carrying()
                    .stream().mapToLong(amount -> amount.count()).sum();
                helper.assertTrue(planned == cells * 64L, "batch uses every available cell and does not exceed pack capacity");
                long peak = carry(helper, fixture, solved, tour.nodes(), cobblestone);
                helper.assertTrue(peak == cells * 64L && Goods.countIn(target, cobblestone) == peak
                    && Goods.countIn(source, cobblestone) == 320 - peak && task.remaining() == 320 - peak,
                    "real transfer commits conserve goods, settle the batch, and leave overflow for later");
                helper.assertTrue(rank == 0 ? peak == 64 : peak > 64, "heavy hauling removes the former one-stack delivery ceiling");
            } finally {
                solver.closed();
            }
        }
        helper.succeed();
    }

}
