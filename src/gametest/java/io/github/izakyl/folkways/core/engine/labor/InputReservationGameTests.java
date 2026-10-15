package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.Planned;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import java.util.stream.IntStream;
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

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class InputReservationGameTests {
    private static final ItemSpec SEEDS = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "input_reservations");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(InputReservationGameTests.class); }

    public record Exported(List<ReservedInput> inputs, List<ItemStack> pack, UUID consumer, UUID resident) { }

    @GameTest(template = "empty")
    public static void independentFetchesToOneConsumerExportAll129PhysicalSeeds(GameTestHelper helper) {
        fetched129(helper);
        helper.succeed();
    }

    // Also used by the optional seed-budget test, so its 129 claim comes from real planning and native hauling.
    public static Exported fetched129(GameTestHelper helper) {
        try (var scene = new Scene(helper)) {
            var planned = scene.first();
            helper.assertTrue(planned.diagnosis().shortfalls().isEmpty(), "three actual seed stashes cover the whole carried need");
            List<UUID> fetches = planned.schedule().tours().get(scene.f.worker.resident().id()).nodes().stream()
                .filter(id -> !id.equals(scene.consumer.id())).toList();
            helper.assertValueEqual(fetches.size(), 3, "the planner really needs three independent source fetches");
            long tick = 1;
            for (UUID fetch : fetches) {
                Node node = scene.solver.plan().vertex(fetch).node();
                helper.assertTrue(node instanceof TransferNode, "the upstream step is the real native inventory mover");
                helper.assertTrue(Commit.run(scene.level, node, scene.f.worker) instanceof Outcome.Done, "the actual source draw commits");
                scene.states.put(fetch, NodeState.SETTLING);
                var beforeHandoff = scene.read(false);
                helper.assertTrue(beforeHandoff.stream().noneMatch(input -> input.node().equals(fetch)),
                    "a committed fetch never keeps its spent source draw reserved");
                helper.assertTrue(beforeHandoff.stream().filter(input -> input.node().equals(scene.consumer.id())
                    && input.producer().filter(fetch::equals).isPresent()).allMatch(input ->
                        input.resident().filter(scene.f.worker.resident().id()::equals).isPresent() && !input.pending()),
                    "before planner hand-off, real goods are protected in the fetcher's actual pack");
                scene.states.put(fetch, NodeState.DONE);
                planned = scene.solver.handle(scene.known(true), List.of(new Message.Started(fetch, scene.f.worker.resident().id()),
                    new Message.Finished(fetch)), tick++);
                helper.assertTrue(planned.diagnosis().shortfalls().isEmpty(), "successive hand-offs do not lose an input obligation");
            }
            helper.assertValueEqual(Goods.countIn(scene.f.worker.pack(), SEEDS), 129L, "native hauling moved all 129 physical seeds");
            var inputs = scene.read(false).stream().filter(input -> input.node().equals(scene.consumer.id())).toList();
            helper.assertValueEqual(inputs.size(), 1, "independent Cargo draws export one summed physical claim");
            helper.assertValueEqual(inputs.getFirst().count(), 129L, "64 plus 64 plus 1 is 129, never their maximum of 64");
            helper.assertTrue(inputs.getFirst().resident().filter(scene.f.worker.resident().id()::equals).isPresent()
                && inputs.getFirst().producer().isEmpty(), "the final Cargo preserves its physical owner after hand-off");
            var wrongGuess = InputReservations.read(scene.solver.plan(), id -> scene.states.getOrDefault(id, NodeState.PENDING), id -> UUID.randomUUID());
            helper.assertTrue(wrongGuess.stream().filter(input -> input.node().equals(scene.consumer.id())).allMatch(input ->
                input.resident().filter(scene.f.worker.resident().id()::equals).isPresent()), "Cargo ownership never falls back to a newly scheduled consumer");
            return new Exported(inputs, IntStream.range(0, scene.f.worker.pack().getContainerSize())
                .mapToObj(scene.f.worker.pack()::getItem).filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList(),
                scene.consumer.id(), scene.f.worker.resident().id());
        }
    }

    @GameTest(template = "empty")
    public static void anUnassignedConsumerDoesNotReserveItsFutureFetchTwice(GameTestHelper helper) {
        try (var scene = new Scene(helper)) {
            scene.first();
            var inputs = scene.read(true);
            helper.assertValueEqual(inputs.stream().filter(input -> !input.pending()).mapToLong(ReservedInput::count).sum(),
                129L, "only the three physical warehouse draws reserve stock before any fetch runs");
            helper.assertValueEqual(inputs.stream().filter(ReservedInput::pending).mapToLong(ReservedInput::count).sum(),
                129L, "the same 129 promised seeds still cover the downstream planned need");
            helper.assertTrue(inputs.stream().filter(input -> input.node().equals(scene.consumer.id())).allMatch(input ->
                input.pending() && input.store().isEmpty() && input.resident().isEmpty()), "an unknown future carrier is never a claim on all warehouses");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void actualFetcherIsRememberedWhenItsRouteChangesBeforeHandoff(GameTestHelper helper) throws Exception {
        try (var scene = new Scene(helper)) {
            var planned = scene.first();
            ColonyLabor labor = new ColonyLabor(new ColonyData(), scene.level.dimension());
            try {
                PlanningFixture.apply(labor, scene.level, planned);
                UUID fetch = planned.schedule().tours().get(scene.f.worker.resident().id()).nodes().getFirst();
                Node node = scene.solver.plan().vertex(fetch).node();
                helper.assertTrue(labor.takeable(fetch, scene.level).isPresent(), "the planned fetch is genuinely ready");
                labor.phases().started(fetch);
                labor.started(fetch, scene.f.worker.resident().id());
                helper.assertTrue(Commit.run(scene.level, node, scene.f.worker) instanceof Outcome.Done, "native inventory movement succeeds");
                labor.phases().handedOver(fetch);
                var schedule = ColonyLabor.class.getDeclaredField("schedule"); schedule.setAccessible(true);
                schedule.set(labor, io.github.izakyl.folkways.core.engine.plan.Schedule.EMPTY);
                var inputs = labor.reservedInputs();
                helper.assertTrue(inputs.stream().noneMatch(input -> input.node().equals(fetch)), "SETTLING inputs are already consumed");
                var delivered = inputs.stream().filter(input -> input.producer().filter(fetch::equals).isPresent()).findFirst().orElseThrow();
                helper.assertTrue(delivered.resident().filter(scene.f.worker.resident().id()::equals).isPresent(),
                    "the real fetcher is retained even when the route no longer assigns it");
                helper.assertValueEqual(Goods.countIn(scene.f.worker.pack(), SEEDS), delivered.count(), "the exported location contains exactly that fetched quantity");
            } finally { labor.close(); }
        }
        helper.succeed();
    }

    private static final class Scene implements AutoCloseable {
        final GameTestHelper helper;
        final ServerLevel level;
        final PlanningFixture f;
        final List<Stash> sources = new ArrayList<>();
        final Map<UUID, NodeState> states = new LinkedHashMap<>();
        final Solving solver = new Solving();
        final Stands stands;
        final Node consumer;

        Scene(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            f = new PlanningFixture(helper, 6);
            Stands.Builder builder = Stands.building();
            int[] quantities = {64, 64, 1};
            for (int index = 0; index < quantities.length; index++) {
                var at = f.source.at(f.source.cell().offset(0, 0, index * 2));
                var stash = new Stash(at); sources.add(stash);
                level.setBlockAndUpdate(at.cell(), Blocks.BARREL.defaultBlockState());
                Containers.at(level, at.cell()).orElseThrow().setItem(0, new ItemStack(Items.WHEAT_SEEDS, quantities[index]));
                builder.container(stash, Set.of(new Stand(at)));
            }
            builder.container(new Stash(f.target), Set.of(new Stand(f.target)));
            stands = builder.done();
            var spec = NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1))
                .vocation(Haul.trade()).needs(new Need(SEEDS, 129, true)).done();
            consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            };
        }

        Planned first() {
            return solver.handle(known(false), List.of(new Message.Submitted(OWNER, Grown.of(consumer))), 0);
        }
        Known known(boolean carried) {
            var holdings = new ArrayList<Stock.Holding>();
            var room = new LinkedHashMap<Stash, Integer>();
            for (Stash source : sources) {
                Container container = Containers.at(level, source.pos().cell()).orElseThrow();
                for (int slot = 0; slot < container.getContainerSize(); slot++) if (!container.getItem(slot).isEmpty()) {
                    holdings.add(new Stock.Holding(source, container.getItem(slot).copy()));
                }
                room.put(source, 26);
            }
            room.put(new Stash(f.target), 27);
            Crew crew = f.crew;
            if (carried) {
                var hand = crew.hands().getFirst();
                var cargo = IntStream.range(0, f.worker.pack().getContainerSize()).mapToObj(f.worker.pack()::getItem)
                    .filter(stack -> !stack.isEmpty()).toList();
                crew = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, hand.pace(), hand.licences(),
                    hand.tools(), Optional.empty(), cargo)));
            }
            return new Known(new Situation(f.ways, new Stock(holdings, room), List.of(), stands), crew.hands());
        }
        List<ReservedInput> read(boolean unknown) {
            return InputReservations.read(solver.plan(), id -> states.getOrDefault(id, NodeState.PENDING),
                id -> unknown ? null : f.worker.resident().id());
        }
        public void close() { solver.closed(); f.worker.body().discard(); }
    }
}
