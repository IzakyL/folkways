package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoundCargoRecoveryGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "bound_cargo");
    private static final ItemSpec SEEDS = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
    private static final ItemSpec BONE = Goods.specOf(Items.BONE_MEAL.getDefaultInstance());

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BoundCargoRecoveryGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void pendingBoundInputStowsOnlyUnclaimedCargoThenCompletes(GameTestHelper helper) {
        recovery(helper, false, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void packBlockedWorkCanRequestStowingAlongsideAnExistingRoute(GameTestHelper helper) {
        recovery(helper, true, true);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fullPackWithNoRouteStillRecoversWithoutAnIdleMessage(GameTestHelper helper) {
        recovery(helper, false, false);
    }

    private static void recovery(GameTestHelper helper, boolean route, boolean claimed) {
        try (var scene = new Scene(helper, claimed)) {
            Node task = scene.task(claimed ? Optional.empty() : Optional.of(scene.hand.id()));
            List<Message> submitted = new ArrayList<>();
            if (route) submitted.add(new Message.Submitted(OWNER, Grown.of(scene.action())));
            submitted.add(new Message.Submitted(OWNER, Grown.of(task)));
            Planned blocked = scene.round(submitted);
            helper.assertTrue(blocked.diagnosis().unassigned().stream().anyMatch(u -> u.why() == Unassigned.Reason.PACK_BUSY),
                "fetching input is blocked by the physically full pack");
            helper.assertTrue(blocked.schedule().tours().containsKey(scene.hand.id()) == route,
                "the request works both with an existing route and with no route at all");
            helper.assertTrue(scene.unloads(blocked).isEmpty() && blocked.laid().stow().contains(scene.hand.id()),
                "the schedule requests ordinary recovery work on the next grow, without idling the resident");
            helper.assertTrue(scene.claims.taken(new Resource.Cargo(scene.hand.id(), SEEDS)) == (claimed ? 1 : 0),
                "carried input binds the pending task to its physical owner");
            Planned recovered = scene.round(List.of());
            var unloads = scene.unloads(recovered);
            helper.assertTrue(unloads.size() == (claimed ? 1 : 2), "each unclaimed stack gets one recovery node");
            helper.assertTrue(unloads.stream().allMatch(node -> node.goods().stream().noneMatch(stack -> stack.is(Items.WHEAT_SEEDS))
                    && node.worker().orElseThrow().equals(scene.hand.id())
                    && recovered.weave().pins().get(node.id()).equals(scene.hand.id())),
                "only the real carrier unloads, and the consumer's claimed seeds stay in the pack");
            var ids = unloads.stream().map(Node::id).toList();
            Planned repeated = scene.round(List.of(Message.ANSWERED));
            helper.assertTrue(scene.unloads(repeated).stream().map(Node::id).toList().equals(ids),
                "repeated grows keep the same recovery nodes and claims");
            helper.assertTrue(scene.claims.taken(new Resource.Cargo(scene.hand.id(), BONE)) == 64
                    && scene.claims.taken(new Resource.Cargo(scene.hand.id(), SEEDS)) == (claimed ? 1 : 0),
                "recovery reserves its own cargo once and preserves pending input claims");
            scene.complete(repeated, task);
            helper.assertTrue(Goods.countIn(scene.f.worker.pack(), SEEDS) == 0
                    && Goods.countIn(scene.source, SEEDS) == 0 && Goods.countIn(scene.target, SEEDS) == 0,
                "the work consumes all twenty-one seeds without ever storing its claimed input");
            helper.assertTrue(Goods.countIn(scene.source, BONE) + Goods.countIn(scene.target, BONE) == 64,
                "the unclaimed cargo reaches a real store");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void workAlreadyInHandDefersRecoveryUntilItSettles(GameTestHelper helper) {
        try (var scene = new Scene(helper, true)) {
            Node active = scene.action();
            Node pending = scene.task(Optional.empty());
            scene.round(List.of(new Message.Submitted(OWNER, Grown.of(active)),
                new Message.Submitted(OWNER, Grown.of(pending))));
            Planned held = scene.round(List.of(new Message.Started(active.id(), scene.hand.id())));
            for (int round = 0; round < 3; round++) {
                helper.assertTrue(scene.unloads(held).isEmpty(), "a queued recovery request cannot disrupt work in hand");
                helper.assertTrue(held.schedule().tours().get(scene.hand.id()).nodes().getFirst().equals(active.id()),
                    "held work stays at the head of the route");
                helper.assertTrue(scene.claims.taken(new Resource.Cargo(scene.hand.id(), SEEDS)) == 1,
                    "held work does not revoke the pending consumer's input claim");
                held = scene.round(List.of(Message.ANSWERED));
            }
            helper.assertTrue(Commit.run(helper.getLevel(), active, scene.f.worker) instanceof Outcome.Done,
                "the held work settles normally");
            Planned freed = scene.round(List.of(new Message.Finished(active.id())));
            helper.assertTrue(scene.unloads(freed).size() == 1, "the deferred request survives until the hand lets go");
            scene.complete(freed, pending);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cargoIsKeptUntilWorkNeedsRoomOrTheResidentIdles(GameTestHelper helper) {
        try (var scene = new Scene(helper, true)) {
            Planned kept = scene.round(List.of(new Message.Submitted(OWNER, Grown.of(scene.action()))));
            for (int round = 0; round < 3; round++) {
                helper.assertTrue(kept.laid().stow().isEmpty() && scene.unloads(kept).isEmpty(),
                    "a full pack alone does not request stowing");
                kept = scene.round(List.of(Message.ANSWERED));
            }
        }
        try (var scene = new Scene(helper, true)) {
            Planned tidy = scene.round(List.of(new Message.Idle(scene.hand.id())));
            helper.assertTrue(scene.unloads(tidy).size() == 2, "genuinely idle residents still tidy their unclaimed packs");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void fullyClaimedCargoIsNeverRecoveredOrRequestedRepeatedly(GameTestHelper helper) {
        try (var scene = new Scene(helper, true)) {
            Node task = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(scene.f.target),
                Stances.at(scene.f.target), Workload.Once.of(10))
                .needs(new Need(SEEDS, 21, true), new Need(BONE, 64, true)).done(), Optional.empty());
            Planned blocked = scene.round(List.of(new Message.Submitted(OWNER, Grown.of(task))));
            helper.assertTrue(blocked.laid().stow().contains(scene.hand.id()), "the full pack blocks input fetching");
            for (int round = 0; round < 3; round++) {
                blocked = scene.round(List.of(Message.ANSWERED));
                helper.assertTrue(scene.unloads(blocked).isEmpty() && blocked.laid().stow().isEmpty(),
                    "a recovery that can free nothing does not keep the stages waking each other");
                helper.assertTrue(scene.claims.taken(new Resource.Cargo(scene.hand.id(), SEEDS)) == 1
                        && scene.claims.taken(new Resource.Cargo(scene.hand.id(), BONE)) == 64,
                    "all pending input reservations survive recovery attempts");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void recoveryWaitsForStoreRoomWithoutRepeatingTheScheduleRequest(GameTestHelper helper) {
        try (var scene = new Scene(helper, true)) {
            for (int slot = 1; slot < scene.source.getContainerSize(); slot++) {
                scene.source.setItem(slot, new ItemStack(Items.DIAMOND, 64));
            }
            for (int slot = 0; slot < scene.target.getContainerSize(); slot++) {
                scene.target.setItem(slot, new ItemStack(Items.DIAMOND, 64));
            }
            Node task = scene.task(Optional.empty());
            scene.round(List.of(new Message.Submitted(OWNER, Grown.of(task))));
            for (int round = 0; round < 3; round++) {
                Planned waiting = scene.round(List.of(Message.ANSWERED));
                helper.assertTrue(scene.unloads(waiting).isEmpty() && waiting.laid().stow().isEmpty(),
                    "full stores leave recovery waiting for a world change");
                helper.assertTrue(waiting.diagnosis().shortfalls().stream().anyMatch(gap -> gap.why() instanceof Why.NoRoom),
                    "the planner asks for store room instead of abandoning the stow request");
            }
            scene.target.clearContent();
            Planned ready = scene.round(List.of(Message.ANSWERED));
            helper.assertTrue(scene.unloads(ready).size() == 1, "the existing request grows when store room appears");
            scene.complete(ready, task);
        }
        helper.succeed();
    }

    private record Action(NodeSpec spec, Optional<UUID> worker) implements Node {
        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    }

    private static final class Scene implements AutoCloseable {
        final GameTestHelper helper;
        final PlanningFixture f;
        final Crew.Hand hand;
        final Container source;
        final Container target;
        final Claims claims = new Claims();
        final Solving solver = new Solving(claims, new Cooldowns());
        long tick;

        Scene(GameTestHelper helper, boolean claimed) {
            this.helper = helper;
            f = new PlanningFixture(helper, 2);
            hand = f.crew.hands().getFirst();
            helper.getLevel().setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(f.target.cell(), Blocks.BARREL.defaultBlockState());
            source = Containers.at(helper.getLevel(), f.source.cell()).orElseThrow();
            target = Containers.at(helper.getLevel(), f.target.cell()).orElseThrow();
            source.setItem(0, new ItemStack(Items.WHEAT_SEEDS, claimed ? 20 : 21));
            f.worker.pack().setItem(0, new ItemStack(claimed ? Items.WHEAT_SEEDS : Items.COBBLESTONE, claimed ? 1 : 64));
            f.worker.pack().setItem(1, new ItemStack(Items.BONE_MEAL, 64));
        }

        Node task(Optional<UUID> worker) {
            return new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
                Stances.at(f.target), Workload.Once.of(10)).needs(new Need(SEEDS, 21, true)).leaves(Leaves.room(1)).done(), worker);
        }

        Node action() {
            return new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target),
                Stances.at(f.target), Workload.Once.of(1)).done(), Optional.of(hand.id()));
        }

        Planned round(List<Message> inbox) {
            List<Stock.Holding> stock = new ArrayList<>();
            stacks(source).forEach(stack -> stock.add(new Stock.Holding(new Stash(f.source), stack)));
            stacks(target).forEach(stack -> stock.add(new Stock.Holding(new Stash(f.target), stack)));
            List<ItemStack> cargo = stacks(f.worker.pack());
            var crew = new Crew.Hand(hand.who(), hand.at(), 2 - cargo.size(), 2, 1, hand.licences(), hand.tools(),
                Optional.empty(), cargo);
            return solver.handle(new Known(f.situation(new Stock(stock,
                Map.of(new Stash(f.source), 27 - stacks(source).size(), new Stash(f.target), 27 - stacks(target).size())),
                List.of()), List.of(crew)), inbox, tick++);
        }

        List<TransferNode> unloads(Planned planned) {
            return planned.weave().vertices().values().stream().map(Vertex::node)
                .filter(TransferNode.class::isInstance).map(TransferNode.class::cast).filter(node -> !node.fetching()).toList();
        }

        void complete(Planned planned, Node task) {
            boolean done = false;
            for (int round = 0; round < 8; round++) {
                Tour tour = planned.schedule().tours().get(hand.id());
                helper.assertTrue(tour != null && !tour.nodes().isEmpty(), "recovery and blocked work remain reachable by Tours");
                List<Message> finished = new ArrayList<>();
                for (UUID id : tour.nodes()) {
                    Node node = planned.weave().vertices().get(id).node();
                    helper.assertTrue(Commit.run(helper.getLevel(), node, f.worker) instanceof Outcome.Done,
                        "the planned route executes against the real pack and barrels: " + node.id());
                    node.ended(helper.getLevel(), Ending.DONE);
                    finished.add(new Message.Finished(id));
                    done |= id.equals(task.id());
                }
                planned = round(finished);
                if (done) {
                    helper.assertTrue(!planned.weave().vertices().containsKey(task.id()), "the completed work leaves the plan");
                    return;
                }
            }
            throw new AssertionError("the bound work did not finish after unloading");
        }

        public void close() { solver.closed(); }
    }

    private static List<ItemStack> stacks(Container container) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (!container.getItem(slot).isEmpty()) stacks.add(container.getItem(slot).copy());
        }
        return stacks;
    }
}
