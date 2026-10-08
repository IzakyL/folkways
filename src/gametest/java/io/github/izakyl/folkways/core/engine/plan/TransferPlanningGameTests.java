package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Gait;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
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
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.plan.haul.HaulRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.Receipt;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode.Endpoint;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
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
public final class TransferPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TransferPlanningGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recoveryQueriesEachStoreOnceAndPreservesUncertainRoutes(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, 1,
            hand.licences(), hand.tools(), Optional.empty(),
            List.of(new ItemStack(Items.BREAD, 64), new ItemStack(Items.BREAD, 64)))));
        var building = Stands.building();
        Map<Stash, Integer> room = new LinkedHashMap<>();
        Map<Set<WorldPos>, Integer> queries = new LinkedHashMap<>();
        for (int i = 0; i < 8; i++) {
            WorldPos at = f.source.at(f.source.cell().offset(i * 3, 0, 0));
            Stash stash = new Stash(at);
            building.container(stash, Set.of(new Stand(at)));
            room.put(stash, 1);
        }
        Ways ways = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                queries.merge(goals, 1, Integer::sum);
                int offset = goals.iterator().next().cell().getX() - f.source.cell().getX();
                if (offset == 0) return Faring.NO;
                if (offset == 3) return Faring.yes(Journey.afoot(1, goals));
                return Faring.LATER;
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                throw new AssertionError("recovery must query its carrier");
            }
        };
        var orchestration = new Weaver(new Claims(), new Cooldowns());
        orchestration.stow(hand.id());
        orchestration.know(new Situation(ways, new Stock(List.of(), room),
            List.of(), building.done()), carrying);
        var weave = orchestration.grow(false);
        helper.assertTrue(queries.size() == 8 && queries.values().stream().allMatch(count -> count == 1),
            "eight stores and multiple cargo stacks require exactly eight journey queries: " + queries);
        helper.assertTrue(weave.vertices().size() == 2 && orchestration.shortfalls().isEmpty(),
            "the reachable store fills first; uncertain stores still accept remaining recovery plans");
        Set<Integer> destinations = new HashSet<>();
        for (Vertex vertex : weave.vertices().values()) {
            TransferNode node = (TransferNode) vertex.node();
            destinations.add(node.spec().site().where().cell().getX() - f.source.cell().getX());
        }
        helper.assertTrue(destinations.size() == 2 && destinations.contains(3)
            && destinations.stream().allMatch(offset -> offset >= 3),
            "reachable storage is preferred and denied storage is excluded");
        helper.assertTrue(weave.pins().values().stream().allMatch(hand.id()::equals),
            "recovery stays bound to the cargo owner");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void missingPartOfAManifestDoesNotMoveAnything(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        source.setItem(0, new ItemStack(Items.IRON_INGOT, 4));
        var node = new TransferNode(UUID.randomUUID(), Endpoint.store(new Stash(f.source)), Endpoint.pack(),
            new Manifest(List.of(new ItemStack(Items.IRON_INGOT, 4), new ItemStack(Items.GOLD_INGOT, 2)), List.of()),
            Stances.at(f.source), new WorkSite.AtBlock(f.source));
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Failed,
            "a partially available manifest must fail");
        helper.assertTrue(source.getItem(0).getCount() == 4 && f.worker.pack().isEmpty(),
            "failure leaves both inventories unchanged");
        source.setItem(1, new ItemStack(Items.GOLD_INGOT, 2));
        for (int slot = 0; slot < f.worker.pack().getContainerSize(); slot++) {
            f.worker.pack().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Failed,
            "full destination must fail");
        helper.assertTrue(source.getItem(0).getCount() == 4 && source.getItem(1).getCount() == 2,
            "a full destination must not extract and return goods");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void planningFixesComponentsAndExecutionCannotSubstituteItems(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        ItemStack named = new ItemStack(Items.BREAD, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("reserved bread"));
        source.setItem(0, named.copy());
        var task = new Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target), Goods.specOf(named), 2);
        var stock = new Stock(List.of(new Stock.Holding(new Stash(f.source), named)),
            Map.of(new Stash(f.source), 26, new Stash(f.target), 27));
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(f.situation(stock, List.of()), f.crew,
                task.goals(), List.of(), 0);
            var tour = solved.schedule().tours().get(f.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2, "fetch and deposit are planned");
            var fetch = (TransferNode) solved.weave().vertices().get(tour.nodes().getFirst()).node();
            var deposit = (TransferNode) solved.weave().vertices().get(tour.nodes().getLast()).node();
            helper.assertTrue(ItemStack.isSameItemSameComponents(fetch.goods().getFirst(), named)
                && ItemStack.isSameItemSameComponents(deposit.goods().getFirst(), named),
                "both ends retain the exact planned components");
            source.setItem(0, new ItemStack(Items.BREAD, 2));
            helper.assertTrue(Commit.run(level, fetch, f.worker) instanceof Outcome.Failed,
                "plain bread cannot replace the named bread after planning");
            helper.assertTrue(source.getItem(0).getCount() == 2 && f.worker.pack().isEmpty(), "no partial mutation");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void failedDepositIsReplannedFromTheSameResidentsPack(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        var target = Containers.at(level, f.target.cell()).orElseThrow();
        source.setItem(0, new ItemStack(Items.BREAD, 2));
        ItemSpec bread = Goods.specOf(source.getItem(0));
        var task = new Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target), bread, 2);
        var goals = task.goals();
        var solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(f.situation(new Stock(List.of(new Stock.Holding(new Stash(f.source),
                source.getItem(0).copy())), Map.of(new Stash(f.source), 26, new Stash(f.target), 27)), List.of()),
                f.crew, goals, List.of(), 0);
            var tour = solved.schedule().tours().get(f.worker.resident().id());
            var fetch = solved.weave().vertices().get(tour.nodes().getFirst()).node();
            var deposit = solved.weave().vertices().get(tour.nodes().getLast()).node();
            helper.assertTrue(Commit.run(level, fetch, f.worker) instanceof Outcome.Done, "fetch succeeds");
            for (int slot = 0; slot < target.getContainerSize(); slot++) {
                target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
            }
            helper.assertTrue(Commit.run(level, deposit, f.worker) instanceof Outcome.Failed, "changed target rejects deposit");
            helper.assertTrue(Goods.countIn(f.worker.pack(), bread) == 2 && source.isEmpty(),
                "failed deposit keeps cargo in its carrier's pack");
            target.clearContent();
            var hand = f.crew.hands().getFirst();
            Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 5, 6, 1,
                hand.licences(), hand.tools(), Optional.empty(), List.of(new ItemStack(Items.BREAD, 2)))));
            var emptied = f.situation(new Stock(List.of(),
                Map.of(new Stash(f.source), 27, new Stash(f.target), 27)), List.of());
            var failed = solver.solve(emptied, carrying, goals,
                List.of(new Message.Finished(fetch.id()), new Message.Failed(deposit.id(), HaulRefusal.NO_ROOM)), 1);
            helper.assertTrue(!failed.weave().vertices().containsKey(deposit.id()),
                "the failed deposit is not grown back by the planner");
            var retried = solver.solve(emptied, carrying, goals, List.of(), 2);
            var again = retried.schedule().tours().get(hand.id());
            helper.assertTrue(again != null && again.nodes().size() == 1,
                "planner reuses carried goods without fetching or unloading them first");
            var transfer = retried.weave().vertices().get(again.nodes().getFirst()).node();
            helper.assertTrue(transfer instanceof TransferNode && Commit.run(level, transfer, f.worker) instanceof Outcome.Done,
                "the replacement is the same transfer primitive");
            helper.assertTrue(Goods.countIn(target, bread) == 2 && f.worker.pack().isEmpty(), "all goods arrive");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void idleCargoIsNotPutAwayAmongAnotherGoodsReserve(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        ItemSpec planks = Goods.specOf(new ItemStack(Items.OAK_PLANKS));
        Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 4, 6, 1, hand.licences(), List.of(),
            Optional.empty(), List.of(new ItemStack(Items.WOODEN_HOE), new ItemStack(Items.OAK_PLANKS, 2)))));
        Map<Stash, Integer> room = Map.of(new Stash(f.source), 27, new Stash(f.target), 27);
        // The walk to a store is as long as the way there, so the store the hand stands at is the nearest.
        Ways measured = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                int ticks = goals.stream()
                    .mapToInt(goal -> (int) Math.round(Math.sqrt(goal.cell().distSqr(from.cell()))))
                    .min().orElse(0);
                return Faring.yes(Journey.afoot(1 + ticks, goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(1, goals));
            }
        };
        var plankOrder = Map.<Stash, List<StoreRule>>of(new Stash(f.target), List.of(new StoreRule.Reserve(f.target, planks)));
        var solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(new Situation(measured, new Stock(List.of(), room, plankOrder), List.of(), f.stands),
                carrying, List.of(), List.of(new Message.Idle(hand.id())), 0);
            Map<Item, Stash> into = new java.util.HashMap<>();
            for (var vertex : solved.weave().vertices().values()) {
                var put = (TransferNode) vertex.node();
                put.goods().forEach(stack -> into.put(stack.getItem(), vertex.plan().into().getFirst()));
            }
            helper.assertTrue(into.get(Items.WOODEN_HOE).equals(new Stash(f.source)),
                "a tool is put away in the farther plain store, not in the nearer store kept for planks: " + into);
            helper.assertTrue(into.get(Items.OAK_PLANKS).equals(new Stash(f.target)),
                "planks still go into the nearest store, which is kept for them: " + into);
        } finally {
            solver.closed();
        }
        solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var onlyOrder = Map.<Stash, List<StoreRule>>of(new Stash(f.source),
                List.of(new StoreRule.Reserve(f.source, planks)), new Stash(f.target),
                List.of(new StoreRule.Reserve(f.target, planks)));
            var solved = solver.solve(f.situation(new Stock(List.of(), room, onlyOrder), List.of()), carrying,
                List.of(), List.of(new Message.Idle(hand.id())), 0);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty() && solved.weave().vertices().size() == 2,
                "with no other store, the tool is put away even where planks are kept, rather than carried for ever");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void idleCargoIsPutAwayInTheNearestStoreWhileNoWayThereIsWorkedOut(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 4, 6, 1, hand.licences(), List.of(),
            Optional.empty(), List.of(new ItemStack(Items.WHEAT_SEEDS, 8)))));
        // Plain stores far off on either side, listed in whatever order the colony keeps them.
        List<WorldPos> far = List.of(f.target.at(f.target.cell().offset(40, 0, 0)),
            f.target.at(f.target.cell().offset(-60, 0, 0)), f.target.at(f.target.cell().offset(0, 0, 50)));
        var building = Stands.building().container(new Stash(f.target), Set.of(new Stand(f.target)));
        Map<Stash, Integer> room = new java.util.HashMap<>(Map.of(new Stash(f.target), 27));
        for (WorldPos store : far) {
            building.container(new Stash(store), Set.of(new Stand(store)));
            room.put(new Stash(store), 27);
        }
        // Every way is still being worked out in the background, as it is just after the colony wakes.
        Ways unknown = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                return Faring.LATER;
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.LATER;
            }
        };
        // The way to the far stores is known, and the one to the store at hand is not yet.
        Ways farKnown = new Ways() {
            public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                if (goals.contains(f.target)) {
                    return Faring.LATER;
                }
                int blocks = goals.stream().mapToInt(goal -> (int) Math.sqrt(goal.cell().distSqr(from.cell())))
                    .min().orElse(0);
                return Faring.yes(Journey.afoot((int) Math.round(blocks / Gait.BLOCKS_PER_TICK), goals));
            }
            public Faring anyoneReaches(Set<WorldPos> goals) {
                return Faring.yes(Journey.afoot(1, goals));
            }
        };
        for (Ways ways : List.of(unknown, farKnown)) {
            var solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
            try {
                var solved = solver.solve(new Situation(ways, new Stock(List.of(), room), List.of(), building.done()),
                    carrying, List.of(), List.of(new Message.Idle(hand.id())), 0);
                var into = solved.weave().vertices().values().stream()
                    .map(vertex -> vertex.plan().into().getFirst()).toList();
                helper.assertTrue(into.equals(List.of(new Stash(f.target))),
                    "seeds go into the store the hand stands at, not one across the colony: " + into);
            } finally {
                solver.closed();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void idleCargoGetsAPlannedTransferAndPersonalSuppliesAreAcquiredThenKept(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        ItemStack bread = new ItemStack(Items.BREAD, 2);
        var hand = f.crew.hands().getFirst();
        Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 5, 6, 1,
            hand.licences(), List.of(), Optional.empty(), List.of(bread))));
        var solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(f.situation(new Stock(List.of(), Map.of(new Stash(f.target), 27)), List.of()),
                carrying, List.of(), List.of(new Message.Idle(hand.id())), 0);
            var tour = solved.schedule().tours().get(hand.id());
            helper.assertTrue(tour != null && tour.nodes().size() == 1, "idle cargo is planner work");
            var transfer = (TransferNode) solved.weave().vertices().get(tour.nodes().getFirst()).node();
            helper.assertTrue(transfer.worker().orElseThrow().equals(hand.id())
                && transfer.goods().getFirst().getCount() == 2, "carrier and amount are fixed by planning");
        } finally {
            solver.closed();
        }
        solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        var personal = new Owed(new WorkSite.AtEntity(hand.id(), f.source), Stances.WHEREVER,
            Goods.specOf(bread), 2);
        try {
            var situation = f.situation(new Stock(List.of(new Stock.Holding(new Stash(f.source), bread)),
                Map.of(new Stash(f.source), 26)), List.of());
            var goals = personal.goals();
            var solved = solver.solve(situation, f.crew, goals, List.of(), 0);
            var tour = solved.schedule().tours().get(hand.id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2
                    && solved.weave().vertices().get(tour.nodes().getFirst()).node() instanceof TransferNode
                    && solved.weave().vertices().get(tour.nodes().getLast()).node() instanceof Receipt,
                "personal supplies are one acquisition, then kept by the one who acquired them");
            solver.solve(situation, f.crew, goals, List.of(new Message.Finished(tour.nodes().getFirst())), 1);
            helper.assertTrue(!personal.complete(), "acquiring the goods is not yet keeping them");
            solved.weave().vertices().get(tour.nodes().getLast()).node().ended(null, Ending.DONE);
            solver.solve(situation, f.crew, goals, List.of(new Message.Finished(tour.nodes().getLast())), 2);
            helper.assertTrue(personal.complete(), "keeping the goods completes the personal delivery");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void personalDeliveryDoesNotCountExistingSuppliesAsNewGoods(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var hand = f.crew.hands().getFirst();
        ItemStack bread = new ItemStack(Items.BREAD);
        Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.source, 5, 6, 1,
            hand.licences(), List.of(), Optional.empty(), List.of(bread))));
        Owed additional = new Owed(new WorkSite.AtEntity(hand.id(), f.source),
            Stances.WHEREVER, Goods.specOf(bread), 1);
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            var solved = solver.solve(f.situation(new Stock(List.of(), Map.of()), List.of()), carrying,
                additional.goals(), List.of(), 0);
            helper.assertTrue(!additional.complete() && !solved.diagnosis().shortfalls().isEmpty(),
                "existing supplies at the destination cannot fulfill an additional delivery");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void producedGoodsKeepComponentsAndTransferAtomically(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
        var target = Containers.at(level, f.target.cell()).orElseThrow();
        ItemStack first = new ItemStack(Items.NETHERITE_CHESTPLATE);
        first.setDamageValue(137);
        first.set(DataComponents.CUSTOM_NAME, Component.literal("upgraded armor"));
        ItemStack second = new ItemStack(Items.NETHERITE_CHESTPLATE);
        second.setDamageValue(58);
        var transfer = new TransferNode(UUID.randomUUID(), Endpoint.pack(), Endpoint.store(new Stash(f.target)),
            new Manifest(List.of(), List.of(new Need(Goods.specOf(first), 2))),
            Stances.at(f.target), new WorkSite.AtBlock(f.target));
        f.worker.pack().setItem(0, first.copy());
        f.worker.pack().setItem(1, new ItemStack(Items.DIAMOND_CHESTPLATE));
        helper.assertTrue(Commit.run(level, transfer, f.worker) instanceof Outcome.Failed,
            "another item cannot substitute for the missing production");
        helper.assertTrue(ItemStack.matches(f.worker.pack().getItem(0), first) && target.isEmpty(),
            "a missing production leaves both inventories unchanged");
        f.worker.pack().setItem(1, second.copy());
        for (int slot = 0; slot < target.getContainerSize(); slot++) {
            target.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        target.setItem(0, ItemStack.EMPTY);
        helper.assertTrue(Commit.run(level, transfer, f.worker) instanceof Outcome.Failed,
            "one free slot cannot receive two differently damaged items");
        helper.assertTrue(target.getItem(0).isEmpty() && ItemStack.matches(f.worker.pack().getItem(0), first),
            "capacity failure must not partially move production");
        target.clearContent();
        helper.assertTrue(Commit.run(level, transfer, f.worker) instanceof Outcome.Done,
            "real production components must not be compared to a default stack");
        helper.assertTrue(f.worker.pack().isEmpty() && ItemStack.matches(target.getItem(0), first)
            && ItemStack.matches(target.getItem(1), second), "damage and names survive the complete transfer");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void futureAlternativesBindOnlyAfterAtomicPickup(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        var target = Containers.at(level, f.target.cell()).orElseThrow();
        var alternatives = ItemSpec.anyOf(List.of(Goods.specOf(new ItemStack(Items.COAL)),
            Goods.specOf(new ItemStack(Items.OAK_PLANKS))));
        var cargo = new Stock(List.of(), Map.of()).manifest(new Stash(f.source), alternatives, 0, 2);
        helper.assertTrue(cargo.exact().isEmpty() && cargo.pending().getFirst().spec().equals(alternatives),
            "future supplies retain their alternatives without inventing concrete items");
        var take = TransferNode.at(Endpoint.store(new Stash(f.source)), Endpoint.pack(), cargo,
            Set.of(new Stand(f.source))).orElseThrow();
        var put = TransferNode.at(Endpoint.pack(), Endpoint.store(new Stash(f.target)), cargo,
            Set.of(new Stand(f.target))).orElseThrow();
        source.setItem(0, new ItemStack(Items.COAL));
        helper.assertTrue(Commit.run(level, take, f.worker) instanceof Outcome.Failed
            && source.getItem(0).getCount() == 1 && f.worker.pack().isEmpty() && !cargo.pending().isEmpty(),
            "partial supply changes neither inventories nor the binding");
        ItemStack named = new ItemStack(Items.OAK_PLANKS, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("actual production"));
        source.setItem(0, named.copy());
        helper.assertTrue(Commit.run(level, take, f.worker) instanceof Outcome.Done && cargo.pending().isEmpty(),
            "successful pickup binds the available alternative");
        f.worker.pack().clearContent();
        f.worker.pack().setItem(0, new ItemStack(Items.COAL, 2));
        helper.assertTrue(Commit.run(level, put, f.worker) instanceof Outcome.Failed && target.isEmpty(),
            "deposit cannot choose another formerly accepted alternative");
        f.worker.pack().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        helper.assertTrue(Commit.run(level, put, f.worker) instanceof Outcome.Failed && target.isEmpty(),
            "deposit cannot substitute different components");
        f.worker.pack().setItem(0, named.copy());
        helper.assertTrue(Commit.run(level, put, f.worker) instanceof Outcome.Done
            && ItemStack.matches(target.getItem(0), named) && f.worker.pack().isEmpty(),
            "deposit preserves the actual pickup including components");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void futureTagAndExactStockCanShareAManifest(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var tag = ItemSpec.of(net.minecraft.tags.ItemTags.PLANKS);
        ItemStack oak = new ItemStack(Items.OAK_PLANKS);
        var cargo = new Stock(List.of(new Stock.Holding(new Stash(f.source), oak)), Map.of())
            .manifest(new Stash(f.source), tag, 0, 3);
        helper.assertTrue(cargo.exact().getFirst().getCount() == 1
            && cargo.pending().getFirst().count() == 2 && cargo.pending().getFirst().spec().equals(tag),
            "known stock is exact while future supplies retain the tag");
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var source = Containers.at(level, f.source.cell()).orElseThrow();
        source.setItem(0, oak.copy());
        source.setItem(1, new ItemStack(Items.BIRCH_PLANKS, 2));
        var take = TransferNode.at(Endpoint.store(new Stash(f.source)), Endpoint.pack(), cargo,
            Set.of(new Stand(f.source))).orElseThrow();
        helper.assertTrue(Commit.run(level, take, f.worker) instanceof Outcome.Done && source.isEmpty()
            && Goods.countIn(f.worker.pack(), tag) == 3 && cargo.pending().isEmpty(),
            "a tag binds to the concrete mixture actually available");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void productionWithAlternativesPlansAcrossEmptyStores(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var alternatives = ItemSpec.anyOf(List.of(Goods.specOf(new ItemStack(Items.COAL)),
            Goods.specOf(new ItemStack(Items.OAK_PLANKS))));
        var domain = net.minecraft.resources.ResourceLocation.withDefaultNamespace("test_supply");
        var sourceSite = new WorkSite.AtBlock(f.source);
        var targetSite = new WorkSite.AtBlock(f.target);
        var producerSpec = NodeSpec.of(java.util.UUID.randomUUID(), domain, sourceSite,
            Stances.at(f.source), Workload.Once.of(1, who -> 1)).gives(new Amount(alternatives, 2)).done();
        var consumerSpec = NodeSpec.of(java.util.UUID.randomUUID(), domain, targetSite,
            Stances.at(f.target), Workload.Once.of(1, who -> 1)).needs(new Need(alternatives, 2)).done();
        Node producer = new Node() {
            public NodeSpec spec() { return producerSpec; }
            public Outcome commit(net.minecraft.server.level.ServerLevel level, Worker who) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty());
            }
        };
        Node consumer = new Node() {
            public NodeSpec spec() { return consumerSpec; }
            public Outcome commit(net.minecraft.server.level.ServerLevel level, Worker who) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty());
            }
        };
        Workshop workshop = new Workshop() {
            public net.minecraft.resources.ResourceLocation id() { return domain; }
            public UUID key() { return UUID.nameUUIDFromBytes(new byte[]{1}); }
            public WorkSite site() { return sourceSite; }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return intent instanceof Produce produce && Goods.overlap(produce.wanted(), alternatives)
                    ? List.of(Refinement.Change.of(Grown.of(producer))) : List.of();
            }
        };
        var stores = f.situation(new Stock(List.of(),
            Map.of(new Stash(f.source), 27, new Stash(f.target), 27)), List.of(workshop));
        var handed = new PlanningFixture.Rounds();
        try {
            var solved = handed.solve(stores, f.crew, List.of(Grown.of(consumer)), List.of(), 0);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty()
                    && solved.weave().vertices().values().stream().noneMatch(v -> v.node() instanceof TransferNode)
                    && solved.weave().groups().stream()
                        .anyMatch(group -> group.contains(producer.id()) && group.contains(consumer.id())),
                "what a pack can hold is handed from its maker to the consumer by one worker, through no store");
            helper.assertTrue(solved.weave().links().isEmpty()
                    && solved.weave().flows().equals(List.of(Flow.from(producer.id(), consumer.id(),
                        alternatives, 2))),
                "handed over, the transfer is laid as its material edge alone, straight from the maker into the "
                    + "consumer: " + solved.weave().flows());
        } finally {
            handed.closed();
        }
        var hand = f.crew.hands().getFirst();
        Crew laden = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 0, 6, 1, hand.licences(),
            hand.tools(), Optional.empty())));
        var solver = new PlanningFixture.Rounds();
        try {
            var solved = solver.solve(stores, laden, List.of(Grown.of(consumer)), List.of(), 0);
            helper.assertTrue(solved.diagnosis().shortfalls().isEmpty(),
                "future alternatives supply the consumer without concrete stock");
            var transfers = solved.weave().vertices().values().stream()
                .map(vertex -> vertex.node()).filter(TransferNode.class::isInstance).toList();
            helper.assertTrue(transfers.size() == 2,
                "with no pack to hand it over in, the maker's store is relayed into the consumer's: "
                    + solved.weave().vertices().size());
            var level = helper.getLevel();
            level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
            var source = Containers.at(level, f.source.cell()).orElseThrow();
            var target = Containers.at(level, f.target.cell()).orElseThrow();
            source.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
            Node take = transfers.stream().filter(node -> node.spec().site().equals(sourceSite)).findFirst().orElseThrow();
            Node put = transfers.stream().filter(node -> node.spec().site().equals(targetSite)).findFirst().orElseThrow();
            helper.assertTrue(Commit.run(level, take, f.worker) instanceof Outcome.Done
                && Commit.run(level, put, f.worker) instanceof Outcome.Done
                && Goods.countIn(target, alternatives) == 2, "planned future cargo binds and arrives");
        } finally {
            solver.closed();
        }
        helper.succeed();
    }

}
