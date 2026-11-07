package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Disposition;
import io.github.izakyl.folkways.core.work.api.HoldingContext;
import io.github.izakyl.folkways.core.work.api.HoldingStep;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.PreemptReason;
import io.github.izakyl.folkways.core.work.api.Settlement;
import io.github.izakyl.folkways.core.work.api.SoftPreempt;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkAction;
import io.github.izakyl.folkways.core.work.api.WorkHolding;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.internal.PickUp;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
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
public final class SettlementGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SettlementGameTests.class);
    }

    @GameTest(template = "empty")
    public static void standardHoldingsSettleGoodsExactlyOnce(GameTestHelper helper) {
        for (boolean continuous : List.of(false, true)) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                Probe node = start(h, continuous, false);
                finish(h, node);
                HoldingContext context = h.labor.execution().context(h.level, h.body, node.placement());
                helper.assertTrue(node.holding.tick(context) instanceof HoldingStep.Done,
                    "repeated terminal ticks stay done");
                helper.assertValueEqual(node.draws, 1, "one action requests one input settlement");
                helper.assertValueEqual(node.stows, 1, "products are stowed once");
                helper.assertValueEqual(node.supplied, 2, "only the planned two goods reach the commit");
                assertGoods(h, 1);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedSettlementReturnsGoods(GameTestHelper helper) {
        for (boolean continuous : List.of(false, true)) {
            try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                Probe node = start(h, continuous, false);
                node.fail = true;
                finish(h, node);
                HoldingContext context = h.labor.execution().context(h.level, h.body, node.placement());
                helper.assertTrue(node.holding.tick(context) instanceof HoldingStep.Failed,
                    "a repeated failure does not attempt another settlement");
                helper.assertValueEqual(node.draws, 1, "failure draws once");
                helper.assertValueEqual(node.stows, 0, "failure stows no products");
                helper.assertValueEqual(node.supplied, 2, "failure happens after the real input draw");
                assertGoods(h, 0);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void throwingSettlementReturnsGoods(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            Probe node = start(h, false, false);
            node.broken = true;
            finish(h, node);
            helper.assertValueEqual(node.draws, 1, "a throwing action is attempted once");
            helper.assertValueEqual(node.stows, 0, "a throwing action produces no goods");
            assertGoods(h, 0);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void endingPrivateWorkConservesGoods(GameTestHelper helper) {
        for (boolean hard : List.of(false, true)) {
            for (boolean afterCommit : List.of(false, true)) {
                try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                    Probe node = start(h, false, true);
                    advanceTo(h, node, afterCommit ? 1 : 0);
                    preempt(h, hard, Disposition.END);
                    h.assertEmpty(node);
                    helper.assertValueEqual(node.draws, afterCommit ? 1 : 0,
                        "ending work initiates no further input settlement");
                    helper.assertValueEqual(node.stows, afterCommit ? 1 : 0,
                        "ending work does not restow completed products");
                    h.runner.preemptHard(h.level, h.body, Disposition.END, PreemptReason.OTHER);
                    assertGoods(h, afterCommit ? 1 : 0);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void suspendingPrivateWorkConservesGoods(GameTestHelper helper) {
        for (boolean hard : List.of(false, true)) {
            for (boolean afterCommit : List.of(false, true)) {
                try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
                    Probe node = start(h, false, true);
                    advanceTo(h, node, afterCommit ? 1 : 0);
                    WorkHolding holding = node.holding;
                    preempt(h, hard, Disposition.SUSPEND);
                    helper.assertTrue(h.colony.isSuspended(node.id()), "suspension retains the outer work");
                    helper.assertValueEqual(node.draws, afterCommit ? 1 : 0,
                        "suspension initiates no input settlement");
                    assertGoods(h, afterCommit ? 1 : 0);
                    h.runner.resume(h.level, h.body);
                    helper.assertTrue(node.holding == holding, "resumption keeps the same holding");
                    finish(h, node);
                    helper.assertValueEqual(node.acquired, 1, "resumption does not reacquire execution");
                    helper.assertValueEqual(node.draws, 2, "each private action settles once across resumption");
                    helper.assertValueEqual(node.stows, 2, "each private action's products are stowed once");
                    assertGoods(h, 2);
                }
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void enginePickupUsesTheNodeHolding(GameTestHelper helper) {
        try (HoldingGameTests.Harness h = new HoldingGameTests.Harness(helper)) {
            ItemEntity drop = new ItemEntity(h.level, h.at.getX() + 0.5D, h.at.getY(), h.at.getZ() + 0.5D,
                new ItemStack(Items.COBBLESTONE, 3));
            h.level.addFreshEntity(drop);
            try {
                Node node = new PickUp(drop.getUUID(), WorldPos.of(h.level, h.at),
                    ItemSpec.of(Items.COBBLESTONE), 3, Stances.WHEREVER);
                h.start(node);
                h.assertHeld(node);
                helper.assertTrue(drop.isAlive(), "taking the node does not execute the pickup");
                h.labor.tickChecked(h.runner, h.level, h.body);
                h.assertEmpty(node);
                helper.assertTrue(!drop.isAlive(), "the default acquired holding picks up the real drop");
                helper.assertValueEqual(h.body.pack().count(Items.COBBLESTONE), 3,
                    "pickup settlement stows the drop once");
            } finally {
                drop.discard();
            }
        }
        helper.succeed();
    }

    private static Probe start(HoldingGameTests.Harness h, boolean continuous, boolean privateSteps) {
        h.body.pack().setItem(0, new ItemStack(Items.COBBLESTONE, 7));
        Probe node = new Probe(h, continuous, privateSteps);
        h.start(node, node.placement());
        return node;
    }

    private static void advanceTo(HoldingGameTests.Harness h, Probe node, int commits) {
        h.labor.tickChecked(h.runner, h.level, h.body);
        for (int tick = 0; node.commits < commits && tick < 30; tick++) {
            h.labor.tickChecked(h.runner, h.level, h.body);
        }
        h.helper.assertValueEqual(node.commits, commits, "the interruption occurs between the chosen actions");
        h.assertHeld(node);
    }

    private static void preempt(HoldingGameTests.Harness h, boolean hard, Disposition result) {
        if (hard) {
            h.runner.preemptHard(h.level, h.body, result, PreemptReason.OTHER);
        } else {
            h.runner.requestSoft(h.level, h.body,
                new SoftPreempt(result, PreemptReason.OTHER, h.level.getGameTime() + 20));
            h.labor.tickChecked(h.runner, h.level, h.body);
        }
    }

    private static void finish(HoldingGameTests.Harness h, Probe node) {
        for (int tick = 0; h.colony.isHeld(node.id()) && tick < 50; tick++) {
            h.labor.tickChecked(h.runner, h.level, h.body);
        }
        h.assertEmpty(node);
    }

    private static void assertGoods(HoldingGameTests.Harness h, int made) {
        h.helper.assertValueEqual(h.body.pack().count(Items.COBBLESTONE), 7 - made,
            "unused or failed inputs are returned and completed inputs are not returned again");
        h.helper.assertValueEqual(h.body.pack().count(Items.DIRT), made, "each completed step makes one product");
        int total = h.body.pack().contents().stream().mapToInt(ItemStack::getCount).sum();
        h.helper.assertValueEqual(total, 7, "input, returned goods and products conserve the total item count");
        h.helper.assertTrue(h.body.doing().isEmpty(), "the holding cleans its action before handing back the body");
    }

    private static final class Probe implements Node {
        private final HoldingGameTests.Harness harness;
        private final boolean privateSteps;
        private final NodeSpec spec;
        private WorkHolding holding;
        private int acquired;
        private int draws;
        private int stows;
        private int commits;
        private int supplied;
        private int ongoing;
        private boolean fail;
        private boolean broken;

        Probe(HoldingGameTests.Harness harness, boolean continuous, boolean privateSteps) {
            this.harness = harness;
            this.privateSteps = privateSteps;
            Workload workload = privateSteps ? new Workload.Custom()
                : continuous ? (Workload.Continuous) (level, who) -> ongoing++ < 5 : Workload.Once.of(5);
            spec = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER, WorkSite.at(harness.level, harness.at),
                Stances.WHEREVER, workload)
                .needs(new Need(ItemSpec.of(Items.COBBLESTONE), privateSteps ? 4 : 2, true)).done();
        }

        Placement placement() {
            return new Placement(Set.of(), List.of(), List.of(), List.of(),
                List.of(new Placement.Draw(Optional.empty(), ItemSpec.of(Items.COBBLESTONE),
                    privateSteps ? 4 : 2)));
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public WorkHolding acquire(HoldingContext context) {
            acquired++;
            WorkHolding execution = privateSteps ? steps() : Node.super.acquire(context);
            holding = new Observed(this, execution);
            return holding;
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            commits++;
            int amount = who.supplied().stream().mapToInt(ItemStack::getCount).sum();
            supplied += amount;
            harness.helper.assertValueEqual(amount, 2, "the settlement supplies the planned input count");
            harness.helper.assertValueEqual(who.pack().countItem(Items.COBBLESTONE), 7 - stows - 2,
                "the real input is removed before the node is called");
            if (broken) {
                throw new IllegalStateException("test settlement failure");
            }
            if (fail) {
                return Outcome.failed(LaborRefusal.WORK_BROKE);
            }
            return new Outcome.Done(List.of(), List.of(new ItemStack(Items.DIRT)), Optional.empty(),
                List.of(new ItemStack(Items.COBBLESTONE)));
        }

        private WorkHolding steps() {
            List<WorkAction> pieces = new java.util.ArrayList<>();
            for (int index = 0; index < 2; index++) {
                NodeSpec inner = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER,
                    spec.site(), Stances.WHEREVER, Workload.Once.of(5))
                    .needs(new Need(ItemSpec.of(Items.COBBLESTONE), 2, true)).done();
                pieces.add(new WorkAction() {
                    public NodeSpec spec() {
                        return inner;
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                        return Probe.this.commit(level, who);
                    }
                });
            }
            return new SequentialHolding(pieces, action -> {});
        }

        HoldingContext observed(HoldingContext context) {
            Settlement settlement = new Settlement() {
                @Override
                public Outcome commit(WorkAction action) {
                    draws++;
                    return context.settlement().commit(action);
                }

                @Override
                public void stow(List<ItemStack> made) {
                    stows++;
                    context.settlement().stow(made);
                }
            };
            return new HoldingContext(context.level(), context.body(), context.worker(), context.actions(),
                settlement, context.holdings(), context.joining());
        }
    }

    private record Observed(Probe probe, WorkHolding execution) implements WorkHolding {
        @Override
        public HoldingStep tick(HoldingContext context) {
            return execution.tick(probe.observed(context));
        }

        @Override
        public void requestSoftPreempt(HoldingContext context, SoftPreempt request) {
            execution.requestSoftPreempt(probe.observed(context), request);
        }

        @Override
        public void hardRelease(HoldingContext context, Disposition result, PreemptReason reason) {
            execution.hardRelease(probe.observed(context), result, reason);
        }

        @Override
        public void suspended(HoldingContext context, PreemptReason reason) {
            execution.suspended(probe.observed(context), reason);
        }

        @Override
        public void resumed(HoldingContext context) {
            execution.resumed(probe.observed(context));
        }
    }

}
