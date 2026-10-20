package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Staged;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.batch.BatchAction;
import io.github.izakyl.folkways.core.api.work.batch.BatchCommit;
import io.github.izakyl.folkways.core.api.work.batch.BatchSession;
import io.github.izakyl.folkways.core.api.work.batch.BatchSpec;
import io.github.izakyl.folkways.core.api.work.batch.InventoryTransfer;
import io.github.izakyl.folkways.core.api.work.batch.StepReceipt;
import io.github.izakyl.folkways.core.api.work.batch.StepResult;
import io.github.izakyl.folkways.core.engine.batch.BatchExecution;
import io.github.izakyl.folkways.core.engine.batch.BatchHolds;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.WeaveView;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BatchExecutionGameTests {
    private static final ItemSpec SEEDS = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
    private static final ItemSpec BEETS = Goods.specOf(Items.BEETROOT_SEEDS.getDefaultInstance());
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "batch");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BatchExecutionGameTests.class); }

    @GameTest(template = "empty")
    public static void exhaustedParentCannotConsumeAnotherTasksPackGoods(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inventory who = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 6)), placed(2));
        AtomicInteger calls = new AtomicInteger();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 2)), 3, i -> action(level, 1, worker -> {
            calls.incrementAndGet();
            return Outcome.done();
        }));
        BatchExecution batch = new BatchExecution(parent);
        helper.assertTrue(run(level, batch, who) instanceof Outcome.Done, "first step consumes its slice");
        helper.assertTrue(run(level, batch, who) instanceof Outcome.Done, "second step consumes the rest");
        helper.assertValueEqual(batch.remainingDraws(who.placement().draws()).getFirst().count(), 0L, "no planned input remains");
        helper.assertTrue(run(level, batch, who) instanceof Outcome.Failed, "third step cannot fall back to unrelated seeds");
        helper.assertValueEqual(calls.get(), 2, "over-budget work never changes the world");
        helper.assertValueEqual(Goods.countIn(who.pack(), SEEDS), 4L, "other work keeps its four seeds");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unusedComponentsReturnAndOnlyActualConsumptionReducesReservation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack named = new ItemStack(Items.WHEAT_SEEDS, 3);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("original supply"));
        ItemStack identity = named.copy();
        Inventory who = new Inventory(new SimpleContainer(named), placed(3));
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 3)), 1, i -> action(level, 3, worker -> {
            helper.assertValueEqual(worker.supplied().getFirst().getCount(), 3, "only the child supplies are handed over");
            return new Outcome.Done(List.of(), List.of(), Optional.empty(), List.of(worker.supplied().getFirst().copyWithCount(2)));
        }));
        BatchExecution batch = new BatchExecution(parent);
        helper.assertTrue(run(level, batch, who) instanceof Outcome.Done, "partial input consumption commits");
        helper.assertValueEqual(Goods.countIn(who.pack(), SEEDS), 2L, "two unused seeds return physically");
        helper.assertTrue(ItemStack.isSameItemSameComponents(identity, who.pack().getItem(0)), "component identity survives restoration");
        helper.assertValueEqual(batch.remainingDraws(who.placement().draws()).getFirst().count(), 2L, "only one seed leaves the parent reservation");
        StepReceipt receipt = parent.receipts.getFirst();
        helper.assertValueEqual(receipt.consumed().getFirst().getCount(), 1, "receipt reports actual consumption");
        receipt.consumed().getFirst().setCount(40);
        helper.assertValueEqual(receipt.consumed().getFirst().getCount(), 1, "receipt access cannot mutate the ledger");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedAndThrowingStepsRestoreTheirInputsWithoutSpendingTheBudget(GameTestHelper helper) {
        for (boolean throwsError : List.of(false, true)) {
            ServerLevel level = helper.getLevel();
            Inventory who = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 2)), placed(2));
            Batch parent = new Batch(level, List.of(new Need(SEEDS, 2)), 1, i -> action(level, 2, worker -> {
                if (throwsError) throw new IllegalStateException("before mutation");
                return Outcome.failed(LaborRefusal.GOODS_GONE);
            }));
            BatchExecution batch = new BatchExecution(parent);
            try {
                helper.assertTrue(run(level, batch, who) instanceof Outcome.Failed, "uncommitted work fails");
                helper.assertTrue(!throwsError, "throwing work must propagate its error");
            } catch (IllegalStateException expected) {
                helper.assertTrue(throwsError, "only the deliberate exception propagates");
            }
            helper.assertValueEqual(Goods.countIn(who.pack(), SEEDS), 2L, "all uncommitted supply is restored");
            helper.assertValueEqual(batch.remainingDraws(who.placement().draws()).getFirst().count(), 2L, "failed work spends no parent quota");
            helper.assertTrue(parent.receipts.isEmpty(), "failure produces no committed receipt");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void acceptedInputsCannotBeRefundedOrCommittedTwice(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Inventory who = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 1)), placed(1));
        AtomicInteger calls = new AtomicInteger();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 1)), 1, i -> new BatchAction() {
            private final NodeSpec spec = piece(level, List.of(new Need(SEEDS, 1)));
            public NodeSpec spec() { return spec; }
            public BatchCommit execute(ServerLevel ignored, Worker worker) {
                calls.incrementAndGet();
                return BatchCommit.accepted(new Outcome.Done(List.of(), List.of(new ItemStack(Items.BONE_MEAL)), Optional.empty()),
                    LaborRefusal.WORK_BROKE);
            }
        });
        BatchExecution batch = new BatchExecution(parent);
        Node node = ((StepResult.Step) batch.next(level, who)).node();
        helper.assertTrue(Commit.run(level, node, who, batch) instanceof Outcome.Done, "accepted mutation settles before stopping");
        batch.notifySettled(level);
        batch.ended(level, node, Ending.DONE);
        helper.assertTrue(Commit.run(level, node, who, batch) instanceof Outcome.Failed, "a repeated attempt is rejected before drawing");
        helper.assertValueEqual(calls.get(), 1, "the appliance receives its input once");
        helper.assertValueEqual(Goods.countIn(who.pack(), SEEDS), 0L, "accepted material is never refunded");
        helper.assertTrue(batch.finish(level, who) instanceof Outcome.Failed, "the parent reports the stop reason");
        helper.assertTrue(parent.receipts.getFirst().stop().isPresent(), "committed failure is explicit in its receipt");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void overlappingDeclarationsUseARealMatchingAndKeepProducerProvenance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemSpec either = ItemSpec.anyOf(List.of(SEEDS, BEETS));
        Inventory who = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS), new ItemStack(Items.BEETROOT_SEEDS)), Placement.NOWHERE);
        Batch parent = new Batch(level, List.of(new Need(either, 1), new Need(SEEDS, 1)), 1,
            i -> new Node() {
                private final NodeSpec spec = piece(level, List.of(new Need(SEEDS, 1), new Need(BEETS, 1)));
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel ignored, Worker worker) { return Outcome.done(); }
            });
        BatchExecution batch = new BatchExecution(parent);
        helper.assertTrue(run(level, batch, who) instanceof Outcome.Done, "specific A plus B fits specific A plus broad A/B");
        helper.assertTrue(!batch.permits(List.of(new ItemStack(Items.WHEAT_SEEDS))), "both overlapping quotas are spent exactly once");

        UUID producerA = UUID.randomUUID();
        UUID producerB = UUID.randomUUID();
        Placement.Draw fromA = new Placement.Draw(Optional.empty(), SEEDS, 1, Optional.empty(), Optional.of(producerA));
        Placement.Draw fromB = new Placement.Draw(Optional.empty(), SEEDS, 1, Optional.empty(), Optional.of(producerB));
        Inventory one = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 2)),
            new Placement(java.util.Set.of(), List.of(), List.of(), List.of(), List.of(fromA, fromB)));
        Batch single = new Batch(level, List.of(new Need(SEEDS, 2)), 1, i -> action(level, 1, worker -> Outcome.done()));
        BatchExecution execution = new BatchExecution(single);
        run(level, execution, one);
        List<Placement.Draw> reordered = execution.remainingDraws(List.of(fromB, fromA));
        helper.assertValueEqual(reordered.get(0).count(), 1L, "another producer's draw is untouched after reordering");
        helper.assertValueEqual(reordered.get(1).count(), 0L, "the actual producer draw carries its debit");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void stagedSupplyStillRollsBackAndCannotBypassTheParentBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (boolean planned : List.of(false, true)) {
            Inventory who = new Inventory(new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 3)), planned ? placed(1) : Placement.NOWHERE);
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger restores = new AtomicInteger();
            Batch parent = new Batch(level, List.of(new Need(SEEDS, planned ? 3 : 1)), 1, i -> new StagedPiece(level, commits, restores));
            BatchExecution batch = new BatchExecution(parent);
            helper.assertTrue(run(level, batch, who) instanceof Outcome.Failed, "staged goods cannot exceed either parent or planned quantities");
            helper.assertValueEqual(commits.get(), 0, "over-budget staged work never changes the world");
            helper.assertValueEqual(restores.get(), 1, "the existing staged restore owns rollback");
            helper.assertValueEqual(Goods.countIn(who.pack(), SEEDS), 3L, "staged supply is conserved");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void realRunnerResumesAnInterruptedAttemptAndCancelsWithoutRefundingCommittedInputs(GameTestHelper helper)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        AtomicInteger attempts = new AtomicInteger();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 2)), 2, i -> {
            attempts.incrementAndGet();
            return new Node() {
                private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(level, BlockPos.ZERO),
                    Stances.WHEREVER, Workload.Once.of(4)).needs(new Need(SEEDS, 1)).done();
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel ignored, Worker who) { return Outcome.done(); }
            };
        });
        try (RunnerRig rig = new RunnerRig(level, parent, 2)) {
            for (int i = 0; i < 30 && attempts.get() == 0; i++) rig.tick();
            helper.assertValueEqual(attempts.get(), 1, "the runner really entered the first attempt");
            rig.runner.letGo(rig.labor, level, rig.body, "urge-preempts");
            helper.assertTrue(parent.receipts.isEmpty(), "the interrupted wind-up never committed");
            for (int i = 0; i < 40 && parent.receipts.isEmpty(); i++) rig.tick();
            helper.assertValueEqual(attempts.get(), 2, "the same logical step resumes with a new attempt");
            helper.assertValueEqual(parent.receipts.size(), 1, "only the completed attempt receives a receipt");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 1L, "one seed was committed");
            helper.assertValueEqual(rig.labor.reservedInputs().stream().filter(input -> input.node().equals(parent.id()))
                .mapToLong(input -> input.count()).sum(), 1L, "exports protect only the remaining parent input");
            rig.labor.phases().cancelled(parent.id());
            for (int i = 0; i < 10; i++) rig.tick();
            helper.assertValueEqual(parent.receipts.size(), 1, "cancellation never executes the next step");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 1L, "cancellation does not refund the committed seed");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scheduleRecoveryPreservesARealPartlyCommittedBatch(GameTestHelper helper)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 2)), 2, i -> action(level, 1,
            who -> new Outcome.Done(List.of(), List.of(new ItemStack(Items.BONE_MEAL)), Optional.empty())));
        RunnerRig rig = new RunnerRig(level, parent, 2);
        try {
            for (int i = 0; i < 40 && parent.receipts.isEmpty(); i++) rig.tick();
            helper.assertValueEqual(parent.receipts.size(), 1, "a real input and output commit before the scheduling fault");
            BatchExecution execution = rig.labor.existingBatch(parent.id());
            var holds = execution.outputHolds();
            helper.assertValueEqual(holds.stream().mapToLong(ReservedInput::count).sum(), 1L, "the real output is reserved");
            SchedulingRecoveryGameTests.Fixture scene = new SchedulingRecoveryGameTests.Fixture(helper, 2, parent,
                placed(2), rig.body.resident(), rig.labor);
            scene.delivered = result -> {
                var route = result.all().get(rig.body.id());
                if (route != null) rig.runner.follow(route.nodes());
            };
            scene.begin();
            scene.afterTicks(() -> {
                try {
                    helper.assertTrue(rig.labor.existingBatch(parent.id()) == execution,
                        "recovery keeps the active execution ledger rather than starting a new batch");
                    helper.assertTrue(execution.outputHolds().equals(holds), "rebuilding routes keeps actual output holds");
                    helper.assertValueEqual(execution.remainingDraws(placed(2).draws()).getFirst().count(), 1L,
                        "only the unspent input remains reserved");
                    helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 1L, "the committed seed is never refunded");
                    helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                        "a failed layout cannot repeat or lose its existing product");
                    helper.assertTrue(scene.published.getLast().all().get(rig.body.id()).nodes().contains(parent.id()),
                        "the recovered route retains the session's worker and holding constraint");
                    for (int i = 0; i < 40 && parent.receipts.size() < 2; i++) rig.tick();
                    helper.assertValueEqual(parent.receipts.size(), 2, "the same session can finish its remaining step");
                    helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 0L, "the original two inputs are consumed once");
                    helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 2L,
                        "the two committed steps produce exactly two real outputs");
                    helper.succeed();
                } finally {
                    rig.close();
                }
            });
        } catch (RuntimeException | Error broken) {
            rig.close();
            throw broken;
        }
    }

    @GameTest(template = "empty")
    public static void receiptObserverFailureCannotLoseOrRepeatTheRealRunnersOutput(GameTestHelper helper)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 1)), 1, i -> action(level, 1,
            who -> new Outcome.Done(List.of(), List.of(new ItemStack(Items.BONE_MEAL)), Optional.empty())));
        parent.throwNotification = true;
        try (RunnerRig rig = new RunnerRig(level, parent, 1)) {
            for (int i = 0; i < 40 && !rig.labor.phases().of(parent.id()).terminal(); i++) rig.tick();
            helper.assertTrue(rig.labor.phases().of(parent.id()).terminal(), "a broken observer stops the parent");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 0L, "the accepted seed is not refunded");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.BONE_MEAL.getDefaultInstance())), 1L,
                "the real runner stows the committed output before notifying observers");
            helper.assertValueEqual(parent.receipts.size(), 1, "notification is attempted once");
            for (int i = 0; i < 10; i++) rig.tick();
            helper.assertValueEqual(parent.receipts.size(), 1, "no retry can produce a second output");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void droppingAPartiallyCommittedBatchNeverReprovisionsItsOriginalAggregate(GameTestHelper helper)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        Batch parent = new Batch(level, List.of(new Need(SEEDS, 2)), 2, i -> action(level, 1, who -> Outcome.done()));
        try (RunnerRig rig = new RunnerRig(level, parent, 2)) {
            for (int i = 0; i < 40 && parent.receipts.isEmpty(); i++) rig.tick();
            helper.assertValueEqual(parent.receipts.size(), 1, "one real step has committed before release");
            rig.runner.letGo(rig.labor, level, rig.body, "reroute");
            helper.assertTrue(rig.labor.phases().of(parent.id()).terminal(), "the original aggregate cannot be rescheduled");
            rig.runner.follow(List.of(parent.id()));
            for (int i = 0; i < 10; i++) rig.tick();
            helper.assertValueEqual(parent.receipts.size(), 1, "following a stale route cannot replay the partial batch");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), SEEDS), 1L, "unspent physical input remains available for recovery");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualOutputLocationsProtectPartialProductsWithoutInventingPackGoodsOrSpace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        WorldPos where = WorldPos.of(level, helper.absolutePos(BlockPos.ZERO));
        Stash stash = Stash.at(where);
        SimpleContainer store = new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 63));
        SimpleContainer pack = new SimpleContainer(new ItemStack(Items.WHEAT_SEEDS, 63));
        List<ItemStack> spills = new ArrayList<>();
        Placement placement = new Placement(java.util.Set.of(), List.of(), List.of(), List.of(stash));
        Inventory who = new Inventory(pack, placement, List.of(new Worker.Store(where, store)), spills);
        List<BatchExecution.Output> placed = Stowing.put(who, placement, List.of(new ItemStack(Items.WHEAT_SEEDS, 3)));
        helper.assertValueEqual(placed.stream().mapToLong(BatchExecution.Output::count).sum(), 2L, "only actual inventory insertions are held");
        helper.assertValueEqual(spills.getFirst().getCount(), 1, "the spilled product is not falsely recorded in a pack");
        Batch parent = new Batch(level, List.of(), 0, i -> { throw new AssertionError(); });
        BatchExecution execution = new BatchExecution(parent);
        execution.produced(placed);
        List<ReservedInput> holds = execution.outputHolds();
        helper.assertTrue(holds.stream().anyMatch(hold -> hold.store().filter(where::equals).isPresent() && hold.count() == 1), "one output remains at the real store");
        helper.assertTrue(holds.stream().anyMatch(hold -> hold.resident().filter(who.resident().id()::equals).isPresent() && hold.count() == 1), "one output remains in the real pack");

        Node consumer = action(level, 3, worker -> Outcome.done());
        Placement promised = new Placement(java.util.Set.of(), List.of(), List.of(), List.of(), List.of(
            new Placement.Draw(Optional.empty(), SEEDS, 3, Optional.empty(), Optional.of(parent.id()))));
        WeaveView graph = new WeaveView();
        graph.apply(Shown.delta(java.util.Map.of(parent.id(), new Vertex(parent.id(), parent, Placement.NOWHERE),
            consumer.id(), new Vertex(consumer.id(), consumer, promised)), List.of(), List.of()));
        List<ReservedInput> exported = InputReservations.read(graph,
            id -> id.equals(parent.id()) ? NodeState.WORKING : NodeState.PENDING, id -> who.resident().id(),
            id -> graph.vertex(id).plan().draws(), id -> id.equals(parent.id()) ? holds : List.of());
        helper.assertValueEqual(exported.stream().filter(ReservedInput::pending).mapToLong(ReservedInput::count).sum(), 1L,
            "only the unproduced part of the downstream promise remains pending");
        helper.assertValueEqual(exported.stream().filter(input -> !input.pending()).mapToLong(ReservedInput::count).sum(), 2L,
            "the real partial product is protected once, at its actual locations");

        Stock raw = new Stock(List.of(new Stock.Holding(stash, store.getItem(0).copy())), java.util.Map.of(stash, 0));
        Crew.Hand hand = new Crew.Hand(who.resident(), where, 0, 2, 1, List.of(),
            List.of(new ItemStack(Items.WHEAT_SEEDS, 64)), Optional.empty(), List.of(pack.getItem(0).copy()));
        Known protectedStock = BatchHolds.exclude(new Known(new Situation(null, raw, List.of(), null), List.of(hand)), holds);
        helper.assertValueEqual(protectedStock.world().stock().available(SEEDS), 63L, "other planner work cannot allocate the held store product");
        helper.assertValueEqual(protectedStock.world().stock().roomAt(stash, SEEDS), 0L, "withholding does not invent a free physical slot");
        helper.assertValueEqual(raw.available(SEEDS), 64L, "the raw inventory snapshot stays physical");
        helper.assertValueEqual(protectedStock.hands().getFirst().cargo().getFirst().getCount(), 63, "one held pack unit is excluded from cargo");
        helper.assertValueEqual(protectedStock.hands().getFirst().tools().getFirst().getCount(), 64, "the same hold is not subtracted from kit a second time");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void auxiliaryCannotBeAnUndeclaredProductionOrConsumableAction(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AtomicInteger commits = new AtomicInteger();
        for (boolean marked : List.of(false, true)) {
            Node untrusted = marked ? new InventoryTransfer() {
                private final NodeSpec spec = piece(level, List.of(new Need(SEEDS, 1)));
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel ignored, Worker who) { commits.incrementAndGet(); return Outcome.done(); }
            } : action(level, 0, who -> { commits.incrementAndGet(); return Outcome.done(); });
            Batch parent = new Batch(level, List.of(), 0, i -> untrusted) {
                public StepResult advance(ServerLevel ignored, Worker who) { return new StepResult.Auxiliary(untrusted); }
            };
            try {
                new BatchExecution(parent).next(level, new Inventory(new SimpleContainer(1), Placement.NOWHERE));
                helper.assertTrue(false, "an unmarked or consumptive auxiliary must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
        helper.assertValueEqual(commits.get(), 0, "auxiliary rejection happens before any world change");
        InventoryTransfer inventedOutput = new InventoryTransfer() {
            private final NodeSpec spec = piece(level, List.of());
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel ignored, Worker who) {
                return new Outcome.Done(List.of(), List.of(new ItemStack(Items.DIAMOND)), Optional.empty());
            }
        };
        helper.assertTrue(Commit.transfer(level, inventedOutput, new Inventory(new SimpleContainer(1), Placement.NOWHERE)) instanceof Outcome.Failed,
            "a lying transfer cannot hand manufactured output to the runner");
        InventoryTransfer inventedUnused = new InventoryTransfer() {
            private final NodeSpec spec = piece(level, List.of());
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel ignored, Worker who) {
                return new Outcome.Done(List.of(), List.of(), Optional.empty(), List.of(new ItemStack(Items.PAPER, 64)));
            }
        };
        Inventory empty = new Inventory(new SimpleContainer(1), Placement.NOWHERE);
        helper.assertTrue(Commit.transfer(level, inventedUnused, empty) instanceof Outcome.Failed,
            "a lying transfer cannot fabricate unused input");
        helper.assertTrue(empty.pack().isEmpty(), "unused validation happens before any normal refund path");
        helper.succeed();
    }

    private static final class StagedPiece implements Node, Staged {
        private final NodeSpec spec;
        private final AtomicInteger commits;
        private final AtomicInteger restores;
        StagedPiece(ServerLevel level, AtomicInteger commits, AtomicInteger restores) {
            spec = piece(level, List.of(new Need(SEEDS, 2)));
            this.commits = commits;
            this.restores = restores;
        }
        public NodeSpec spec() { return spec; }
        public Drawn draw(ServerLevel level, Worker who) {
            return new Taken(List.of(Containers.extract(who.pack(), stack -> Goods.matches(stack, SEEDS), 2)));
        }
        public void restore(ServerLevel level, Worker who, List<ItemStack> goods) {
            restores.incrementAndGet();
            goods.forEach(stack -> Containers.insert(who.pack(), stack));
        }
        public Outcome commit(ServerLevel level, Worker who) { commits.incrementAndGet(); return Outcome.done(); }
    }

    private static Outcome run(ServerLevel level, BatchExecution batch, Worker who) {
        Node node = ((StepResult.Step) batch.next(level, who)).node();
        Outcome outcome = Commit.run(level, node, who, batch);
        batch.notifySettled(level);
        batch.ended(level, node, outcome instanceof Outcome.Done ? Ending.DONE : new Ending.Failed(node.id(), LaborRefusal.GOODS_GONE));
        return outcome;
    }

    private static Node action(ServerLevel level, long count, Function<Worker, Outcome> commit) {
        return new Node() {
            private final NodeSpec spec = piece(level, List.of(new Need(SEEDS, count)));
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel ignored, Worker who) { return commit.apply(who); }
        };
    }

    private static NodeSpec piece(ServerLevel level, List<Need> needs) {
        return NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(level, BlockPos.ZERO), Stances.WHEREVER,
            Workload.Once.of(0)).needs(needs).done();
    }

    private static Placement placed(long count) {
        return new Placement(java.util.Set.of(), List.of(), List.of(), List.of(),
            List.of(new Placement.Draw(Optional.empty(), SEEDS, count)));
    }

    private static class Batch implements Node, BatchSession {
        private final NodeSpec spec;
        private final int steps;
        private final Function<Integer, Node> pieces;
        private final List<StepReceipt> receipts = new ArrayList<>();
        private boolean throwNotification;
        Batch(ServerLevel level, List<Need> needs, int steps, Function<Integer, Node> pieces) {
            this.steps = steps;
            this.pieces = pieces;
            spec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(level, BlockPos.ZERO), Stances.WHEREVER, this)
                .needs(needs).done();
        }
        public NodeSpec spec() { return spec; }
        public BatchSpec batch() { return BatchSpec.of(id()); }
        public StepResult advance(ServerLevel level, Worker who) {
            return receipts.size() == steps ? new StepResult.Complete()
                : new StepResult.Step("step/" + receipts.size(), pieces.apply(receipts.size()));
        }
        public void settled(ServerLevel level, StepReceipt receipt) {
            receipts.add(receipt);
            if (throwNotification) throw new IllegalStateException("observer failed after commit");
        }
        public Outcome commit(ServerLevel level, Worker who) { return finish(level, who); }
    }

    private static final class RunnerRig implements AutoCloseable {
        private final ServerLevel level;
        private final ColonyLabor labor;
        private final Body body;
        private final BodyRunner runner;
        RunnerRig(ServerLevel level, Node parent, int seeds) throws ReflectiveOperationException {
            this.level = level;
            labor = new ColonyLabor(new ColonyData(), level.dimension());
            body = PersonBody.RESIDENT.get().create(level);
            body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, seeds));
            runner = new BodyRunner(body.id());
            PlanningFixture.apply(labor, level, List.of(), Shown.delta(java.util.Map.of(parent.id(),
                new Vertex(parent.id(), parent, placed(seeds))), List.of(), List.of()));
            runner.follow(List.of(parent.id()));
        }
        void tick() { runner.tick(labor, level, body); }
        public void close() {
            runner.drop(labor);
            labor.close();
            body.mob().discard();
        }
    }

    private record Inventory(SimpleContainer pack, Placement placement, List<Store> within, List<ItemStack> spills) implements Worker {
        private static final Resident RESIDENT = new Resident(UUID.randomUUID(), OWNER);
        Inventory(SimpleContainer pack, Placement placement) { this(pack, placement, List.of(), null); }
        public Resident resident() { return RESIDENT; }
        public Mob body() { return null; }
        public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
        public void spill(ItemStack stack) {
            if (stack.isEmpty()) return;
            if (spills == null) throw new AssertionError("unexpected spill");
            spills.add(stack.copy());
        }
        public Effects effects() { return Effects.NONE; }
        public List<Node> route() { return List.of(); }
    }
}
