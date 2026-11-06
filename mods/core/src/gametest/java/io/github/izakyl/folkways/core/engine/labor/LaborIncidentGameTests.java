package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LaborIncidentGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(LaborIncidentGameTests.class); }

    @GameTest(template = "empty")
    public static void fullPackDoesNotReceivePickupUntilSpaceIsAvailable(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = new PlanningFixture(helper, 1);
        var hand = fixture.crew.hands().getFirst();
        ItemStack cargo = new ItemStack(Items.WHEAT, 64);
        fixture.worker.pack().setItem(0, cargo);
        ItemEntity drop = drop(level, fixture.source.block(level));
        PickUp node = pickUp(drop, fixture.source);
        var world = fixture.situation(new Stock(List.of(), Map.of()), List.of());
        var full = full(hand, cargo);
        Solving solver = new Solving();
        try {
            var blocked = solver.handle(new Known(world, List.of(full)),
                List.of(new Message.Submitted(Salvage.DOMAIN, Grown.of(node))), 0);
            helper.assertTrue(blocked.schedule().tours().values().stream()
                .noneMatch(tour -> tour.nodes().contains(node.id())), "a full pack must not receive pickup work");
            helper.assertTrue(drop.isAlive(), "the original ground item remains while pickup is unassigned");
            for (int round = 1; round <= 3; round++) {
                var waiting = solver.handle(new Known(world, List.of(full)), List.of(Message.ANSWERED), round);
                helper.assertTrue(waiting.schedule().tours().values().stream()
                    .noneMatch(tour -> tour.nodes().contains(node.id())), "replanning cannot assign the full pack");
                List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, drop.getBoundingBox().inflate(2));
                helper.assertTrue(drops.size() == 1 && drops.getFirst() == drop && drop.isAlive(),
                    "replanning leaves the same item without creating replacement drops");
            }
            fixture.worker.pack().setItem(0, ItemStack.EMPTY);
            var assigned = solver.handle(new Known(world, List.of(free(hand))), List.of(Message.ANSWERED), 4);
            helper.assertTrue(assigned.schedule().tours().values().stream()
                .anyMatch(tour -> tour.nodes().contains(node.id())), "pickup is assigned after space opens");
            Outcome outcome = Commit.run(level, node, fixture.worker);
            helper.assertTrue(outcome instanceof Outcome.Done, "the assigned pickup succeeds");
            Worker worker = worker(helper, fixture, Placement.NOWHERE, List.of());
            Stowing.put(worker, worker.placement(), ((Outcome.Done) outcome).made());
            helper.assertTrue(!drop.isAlive() && fixture.worker.pack().getItem(0).is(Items.IRON_SWORD),
                "the sword reaches the pack without spilling");
        } finally {
            solver.closed();
            fixture.worker.body().discard();
            drop.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aStoreLetsThePlannerEmptyThePackBeforePickup(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = new PlanningFixture(helper, 1);
        var hand = fixture.crew.hands().getFirst();
        ItemStack cargo = new ItemStack(Items.WHEAT, 64);
        fixture.worker.pack().setItem(0, cargo);
        BlockPos barrel = fixture.target.block(level);
        level.setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        Container store = Containers.at(level, barrel).orElseThrow();
        Stash stash = Stash.at(fixture.target);
        var world = fixture.situation(new Stock(List.of(), Map.of(stash, 27)), List.of());
        var full = full(hand, cargo);
        ItemEntity drop = drop(level, fixture.source.block(level));
        PickUp node = pickUp(drop, fixture.source);
        Solving solver = new Solving();
        try {
            var blocked = solver.handle(new Known(world, List.of(full)),
                List.of(new Message.Submitted(Salvage.DOMAIN, Grown.of(node))), 0);
            helper.assertTrue(blocked.laid().stow().contains(hand.id()), "a full pack requests ordinary recovery");
            var recovered = solver.handle(new Known(world, List.of(full)), List.of(), 1);
            var transfer = recovered.weave().vertices().values().stream()
                .filter(vertex -> vertex.node() instanceof TransferNode)
                .findFirst().orElseThrow();
            Worker mover = worker(helper, fixture, transfer.plan(), List.of(new Worker.Store(fixture.target, store)));
            helper.assertTrue(Commit.run(level, transfer.node(), mover) instanceof Outcome.Done,
                "the planned recovery moves cargo to the store");
            helper.assertTrue(fixture.worker.pack().isEmpty() && store.getItem(0).is(Items.WHEAT),
                "recovery frees the real pack");
            var assigned = solver.handle(new Known(world, List.of(free(hand))),
                List.of(new Message.Finished(transfer.id()), Message.ANSWERED), 2);
            helper.assertTrue(assigned.schedule().tours().values().stream()
                .anyMatch(tour -> tour.nodes().contains(node.id())), "pickup is assigned after recovery");
            Outcome result = Commit.run(level, node, fixture.worker);
            helper.assertTrue(result instanceof Outcome.Done, "pickup succeeds after recovery");
            Worker picker = worker(helper, fixture, Placement.NOWHERE, List.of());
            Stowing.put(picker, picker.placement(), ((Outcome.Done) result).made());
            helper.assertTrue(!drop.isAlive() && fixture.worker.pack().getItem(0).is(Items.IRON_SWORD),
                "the dropped sword is collected without spilling");
        } finally {
            solver.closed();
            fixture.worker.body().discard();
            drop.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pickupGoesToTheResidentWithSpace(GameTestHelper helper) {
        var level = helper.getLevel();
        var first = new PlanningFixture(helper, 1);
        var second = new PlanningFixture(helper, 1);
        var fullHand = first.crew.hands().getFirst();
        var freeHand = second.crew.hands().getFirst();
        ItemStack cargo = new ItemStack(Items.WHEAT, 64);
        first.worker.pack().setItem(0, cargo);
        var full = full(fullHand, cargo);
        ItemEntity drop = drop(level, first.source.block(level));
        PickUp node = pickUp(drop, first.source);
        var world = first.situation(new Stock(List.of(), Map.of()), List.of());
        Solving solver = new Solving();
        try {
            var planned = solver.handle(new Known(world, List.of(full, freeHand)),
                List.of(new Message.Submitted(Salvage.DOMAIN, Grown.of(node))), 0);
            helper.assertTrue(planned.schedule().tours().get(freeHand.id()) != null
                && planned.schedule().tours().get(freeHand.id()).nodes().contains(node.id()),
                "the resident with free space receives the pickup");
            helper.assertTrue(planned.schedule().tours().get(fullHand.id()) == null
                || !planned.schedule().tours().get(fullHand.id()).nodes().contains(node.id()),
                "the full resident does not receive it");
        } finally {
            solver.closed();
            first.worker.body().discard();
            second.worker.body().discard();
            drop.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pickupCanPlacePromisedOutputDirectlyIntoAStore(GameTestHelper helper) {
        var level = helper.getLevel();
        var fixture = new PlanningFixture(helper, 1);
        var hand = fixture.crew.hands().getFirst();
        ItemStack cargo = new ItemStack(Items.WHEAT, 64);
        fixture.worker.pack().setItem(0, cargo);
        var full = full(hand, cargo);
        BlockPos source = fixture.source.block(level);
        level.setBlockAndUpdate(source, Blocks.BARREL.defaultBlockState());
        Container store = Containers.at(level, source).orElseThrow();
        ItemEntity drop = drop(level, source);
        var goods = Goods.specOf(drop.getItem());
        PickUp pickup = pickUp(drop, fixture.source);
        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath("folkways_test", "pickup_store");
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), owner, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(goods, 1)).done();
        Node consumer = Nodes.done(spec);
        Workshop workshop = new Workshop() {
            public ResourceLocation id() { return owner; }

            public UUID key() { return pickup.id(); }

            public WorkSite site() { return pickup.spec().site(); }

            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return intent instanceof Produce produce && Goods.overlap(produce.wanted(), goods)
                    ? List.of(Refinement.Change.of(Grown.of(pickup))) : List.of();
            }
        };
        Stash from = Stash.at(fixture.source);
        Stash into = Stash.at(fixture.target);
        var world = fixture.situation(new Stock(List.of(), Map.of(from, 27, into, 27)), List.of(workshop));
        Solving solver = new Solving();
        try {
            var planned = solver.handle(new Known(world, List.of(full)),
                List.of(new Message.Submitted(owner, Grown.of(consumer))), 0);
            var vertex = planned.weave().vertices().get(pickup.id());
            helper.assertTrue(vertex != null && vertex.plan().into().contains(from),
                "the produced sword is planned into the reachable store");
            helper.assertTrue(planned.schedule().tours().values().stream()
                .anyMatch(tour -> tour.nodes().contains(pickup.id())),
                "a direct store placement must not require a free pack cell");
            Worker picker = worker(helper, fixture, vertex.plan(), List.of(new Worker.Store(fixture.source, store)));
            Outcome result = Commit.run(level, pickup, picker);
            helper.assertTrue(result instanceof Outcome.Done, "the planned pickup succeeds");
            Stowing.put(picker, picker.placement(), ((Outcome.Done) result).made());
            helper.assertTrue(!drop.isAlive() && store.getItem(0).is(Items.IRON_SWORD)
                && fixture.worker.pack().getItem(0).is(Items.WHEAT),
                "the sword goes directly into the store and the full pack stays intact");
        } finally {
            solver.closed();
            fixture.worker.body().discard();
            drop.discard();
        }
        helper.succeed();
    }

    // The fixture's resident working to a plan, with these stores at hand; it never spills.
    private static Worker worker(GameTestHelper helper, PlanningFixture fixture, Placement placement,
                                 List<Worker.Store> stores) {
        return TestWorker.of(fixture.worker.body(), fixture.worker.resident(), fixture.worker.pack()).within(stores)
            .spill(stack -> helper.assertTrue(stack.isEmpty(), "the planned work does not spill"))
            .placement(placement);
    }

    // A hand with its one pack cell taken by `cargo`.
    private static Crew.Hand full(Crew.Hand hand, ItemStack cargo) {
        return new Crew.Hand(hand.who(), hand.at(), 0, 1, hand.pace(), hand.licences(), hand.tools(),
            Optional.empty(), List.of(cargo));
    }

    private static Crew.Hand free(Crew.Hand hand) {
        return new Crew.Hand(hand.who(), hand.at(), 1, 1, hand.pace(), hand.licences(), hand.tools(),
            Optional.empty(), List.of());
    }

    // An iron sword lying on the ground.
    private static ItemEntity drop(ServerLevel level, BlockPos at) {
        ItemEntity drop = new ItemEntity(level, at.getX(), at.getY(), at.getZ(), new ItemStack(Items.IRON_SWORD));
        level.addFreshEntity(drop);
        return drop;
    }

    private static PickUp pickUp(ItemEntity drop, WorldPos at) {
        return new PickUp(drop.getUUID(), at, Goods.specOf(drop.getItem()), 1, Stances.at(at));
    }

    @GameTest(template = "empty")
    public static void pickupDiagnosticsIdentifyTheActualDropAndKeepUnknownCausesExplicit(GameTestHelper helper) {
        var level = helper.getLevel();
        var body = PersonBody.RESIDENT.get().create(level);
        var at = helper.absolutePos(new BlockPos(1, 1, 1));
        body.mob().setPos(at.getX(), at.getY(), at.getZ());
        body.mob().setCustomName(Component.literal("Pickup witness"));
        ItemStack stack = new ItemStack(Items.WHEAT, 7);
        ItemEntity drop = new ItemEntity(level, at.getX(), at.getY(), at.getZ(), stack);
        level.addFreshEntity(drop);
        var node = new PickUp(drop.getUUID(), WorldPos.of(level, at), Goods.specOf(stack), 7, Stances.WHEREVER);
        UUID colony = UUID.randomUUID();
        List<LaborIncident> events = new ArrayList<>();
        Consumer<LaborIncident> listener = event -> { if (event.colony().equals(colony)) events.add(event); };
        NeoForge.EVENT_BUS.addListener(listener);
        LaborIncident.subscriptions(Set.of(colony));
        try {
            LaborIncident.work(colony, level, body, node, "urge-preempts/flee", () -> LaborIncident.surroundings(body));
            String json = Component.Serializer.toJson(events.getFirst().message(), level.registryAccess());
            helper.assertTrue(json.contains("Pickup witness") && json.contains("item.minecraft.wheat")
                    && json.contains(drop.getUUID().toString().substring(0, 8)) && json.contains("unknown"),
                "the message retains the real item, drop ID and explicit unknown context as client components");
        } finally {
            LaborIncident.subscriptions(Set.of());
            NeoForge.EVENT_BUS.unregister(listener);
            drop.discard();
            body.mob().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unsubscribedColoniesBuildNothingAndPostNoEvents(GameTestHelper helper) {
        UUID colony = UUID.randomUUID();
        AtomicInteger built = new AtomicInteger();
        List<LaborIncident> events = new ArrayList<>();
        Consumer<LaborIncident> listener = event -> { if (event.colony().equals(colony)) events.add(event); };
        NeoForge.EVENT_BUS.addListener(listener);
        try {
            for (Set<UUID> watching : List.of(Set.<UUID>of(), Set.of(UUID.randomUUID()))) {
                LaborIncident.subscriptions(watching);
                LaborIncident.publish(colony, helper.getLevel().dimension(), "disabled", () -> {
                    built.incrementAndGet();
                    return Component.literal("disabled");
                });
                // No world or worker may be inspected before checking the subscription.
                LaborIncident.work(colony, null, null, null, "disabled", () -> {
                    built.incrementAndGet();
                    return Component.literal("disabled");
                });
            }
            helper.assertValueEqual(built.get(), 0, "unsubscribed detail suppliers must remain unevaluated");
            helper.assertValueEqual(events.size(), 0, "unsubscribed incidents must not reach the event bus");
        } finally {
            LaborIncident.subscriptions(Set.of());
            NeoForge.EVENT_BUS.unregister(listener);
        }
        helper.succeed();
    }
}
