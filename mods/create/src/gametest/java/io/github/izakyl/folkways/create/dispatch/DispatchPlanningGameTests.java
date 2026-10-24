package io.github.izakyl.folkways.create.dispatch;

import io.github.izakyl.folkways.create.FolkwaysCreateMod;
import com.simibubi.create.content.logistics.box.PackageItem;
import com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity;
import com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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

@EventBusSubscriber(modid = FolkwaysCreateMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DispatchPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DispatchPlanningGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void acceptedPendingNodeSurvivesReplanningWithoutOrderingAgain(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            UUID network = UUID.randomUUID();
            Depot depot = depot(fixture, nodes, network, List.of(), fake.supply());
            Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), iron, 32);
            List<Grown> goals = delivery.goals();
            Stock stock = new Stock(List.of(), Map.of(new Stash(fixture.target), 2));
            var situation = fixture.situation(stock, List.of(depot));
            var solved = solver.solve(situation, fixture.crew, goals, List.of(), 0);
            helper.assertTrue(solved.weave().vertices().size() == 2 && nodes.size() == 1,
                "collection and delivery must form one carrying chain without requiring a staging chest");
            CollectNode collect = nodes.values().iterator().next();
            helper.assertTrue(fake.sent.isEmpty(), "speculative planning must not send external orders");
            ColonyLabor labor = new ColonyLabor(new ColonyData(), helper.getLevel().dimension());
            PlanningFixture.apply(labor, helper.getLevel(), solved);
            helper.assertTrue(fake.sent.equals(List.of(32L)) && !collect.ready(helper.getLevel()),
                "accepting the graph orders once and keeps collection pending");
            for (long tick : List.of(20L, 600L, 1200L)) {
                solved = solver.solve(situation, fixture.crew, goals, List.of(), tick);
                PlanningFixture.apply(labor, helper.getLevel(), solved);
                helper.assertTrue(solved.weave().vertices().containsKey(collect.id()) && nodes.size() == 1,
                    "the outstanding producer must remain on the graph");
            }
            var hand = fixture.crew.hands().getFirst();
            var larger = new Crew(List.of(
                new Crew.Hand(hand.who(), hand.at(),
                    12, 128, hand.pace(), hand.licences(), hand.tools(), hand.holding())));
            solved = solver.solve(situation, larger, goals, List.of(), 1220);
            PlanningFixture.apply(labor, helper.getLevel(), solved);
            helper.assertTrue(solved.weave().vertices().containsKey(collect.id()),
                "a capacity change must not recreate a producer which has already ordered goods");
            helper.assertTrue(fake.sent.equals(List.of(32L)), "replanning must not submit another request");
            var full = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(),
                0, 12, hand.pace(), hand.licences(), hand.tools(), Optional.empty())));
            var waiting = solver.solve(situation, full, goals, List.of(), 1221);
            PlanningFixture.apply(labor, helper.getLevel(), waiting);
            helper.assertTrue(waiting.weave().vertices().containsKey(collect.id())
                    && fake.sent.equals(List.of(32L)),
                "loss of pack room must not revoke or resend an external order");
            solved = solver.solve(situation, larger, goals, List.of(), 1222);
            fake.arrived.put(0, 8);
            helper.assertTrue(!collect.ready(helper.getLevel()), "partial arrival keeps the same node pending");
            fake.arrived.put(0, 32);
            helper.assertTrue(collect.ready(helper.getLevel()), "full arrival makes the existing node available");
            helper.assertTrue(fake.sent.equals(List.of(32L)), "arrival does not send another order");
            helper.getLevel().setBlockAndUpdate(fixture.target.cell(),
                Blocks.BARREL.defaultBlockState());
            var tour = solved.schedule().tours().get(fixture.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2,
                "one worker must collect then deliver without an intermediate inventory");
            for (UUID nodeId : tour.nodes()) {
                var vertex = solved.weave().vertices().get(nodeId);
                var result = Commit.run(helper.getLevel(), vertex.node(), fixture.worker);
                helper.assertTrue(result instanceof Outcome.Done,
                    "both actions in the direct delivery must succeed");
                for (ItemStack made : ((Outcome.Done) result).made()) {
                    Containers.insert(fixture.worker.pack(), made);
                }
            }
            var destination = Containers
                .at(helper.getLevel(), fixture.target.cell()).orElseThrow();
            helper.assertTrue(Goods.countIn(destination, iron) == 32,
                "the arrived order must reach its destination in full");
            helper.succeed();
        } finally {
            solver.closed();
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void rejectedSpeculationNeverOrdersAndPartialSupplyIsNotOverstated(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        fake.stock = 16;
        PackageNetworks.install(fake.adapter());
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            UUID network = UUID.randomUUID();
            Depot depot = depot(fixture, nodes, network, List.of(), fake.supply());
            Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), iron, 32);
            var solved = solver.solve(fixture.situation(new Stock(List.of(),
                Map.of(new Stash(fixture.target), 2)), List.of(depot)), fixture.crew,
                delivery.goals(), List.of(), 0);
            ColonyLabor labor = new ColonyLabor(new ColonyData(), helper.getLevel().dimension());
            PlanningFixture.apply(labor, helper.getLevel(), solved);
            helper.assertTrue(solved.weave().vertices().isEmpty() && nodes.isEmpty() && fake.sent.isEmpty(),
                "abandoned candidates must release their reservations without ordering");
            fake.stock = 20;
            CollectNode collect = new CollectNode(fixture.source, Stances.at(fixture.source), iron,
                32, 1, 20, Optional.of(network), "home", new Eta(), Map.of());
            helper.assertTrue(collect.planned(helper.getLevel()).orElseThrow() == DispatchRefusal.NO_SUPPLY,
                "a changed supply must fail explicitly instead of claiming a full delivery");
            helper.assertTrue(fake.sent.isEmpty(), "insufficient supply must not create an ambiguous partial order");
            helper.succeed();
        } finally {
            solver.closed();
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void collectionsOrderInFullAndTakeOnlyTheirOwnParcels(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetwork.Parcel stray = new PackageNetwork.Parcel(List.of(new ItemStack(Items.IRON_INGOT, 10)), OptionalInt.empty());
        fake.strays.add(stray);
        PackageNetworks.install(fake.adapter());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            UUID network = UUID.randomUUID();
            Depot depot = depot(fixture, nodes, network, List.of(stray), fake.supply());
            Produce eight = Produce.of(ResourceLocation.withDefaultNamespace("test"),
                new WorkSite.AtBlock(fixture.source), iron, 8, Produce.UNBOUNDED);
            Refinement.Change first = depot.options(eight, Grown.of(eight)).getFirst();
            Refinement.Change second = depot.options(eight, Grown.of(eight)).getFirst();
            helper.assertTrue(first.holds().stream().allMatch(hold -> hold.take().isEmpty())
                    && second.holds().stream().allMatch(hold -> hold.take().isEmpty()),
                "both collections are promised from the network's stock");
            CollectNode one = (CollectNode) first.replacement().nodes().getFirst();
            CollectNode two = (CollectNode) second.replacement().nodes().getFirst();
            helper.assertTrue(one.planned(helper.getLevel()).isEmpty() && two.planned(helper.getLevel()).isEmpty(),
                "both reserved tasks can be accepted");
            helper.assertTrue(fake.sent.equals(List.of(8L, 8L)),
                "each collection orders its whole count, whatever strays sit at the port");
            helper.assertTrue(!one.ready(helper.getLevel()) && !two.ready(helper.getLevel()),
                "a stray parcel makes no collection ready");
            fake.arrived.put(1, 8);
            helper.assertTrue(!one.ready(helper.getLevel()) && two.ready(helper.getLevel()),
                "a parcel readies only the collection that ordered it");
            Outcome taken = Commit.run(helper.getLevel(), two, fixture.worker);
            helper.assertTrue(taken instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 8
                    && fake.arrived.isEmpty() && fake.strays.size() == 1,
                "a collection takes its own parcel whole and leaves the stray");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void strayParcelsAreClearedAndOwnedOnesKept(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        fake.strays.add(new PackageNetwork.Parcel(List.of(new ItemStack(Items.IRON_INGOT, 10)), OptionalInt.empty()));
        PackageNetworks.install(fake.adapter());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            CollectNode collect = new CollectNode(fixture.source, Stances.at(fixture.source), iron,
                4, 1, 20, Optional.of(UUID.randomUUID()), "home", new Eta(), nodes);
            nodes.put(collect.id(), collect);
            helper.assertTrue(collect.planned(helper.getLevel()).isEmpty(), "the collection orders");
            fake.arrived.put(0, 4);
            StrayNode clear = new StrayNode(fixture.source, Stances.at(fixture.source), 1, nodes);
            helper.assertTrue(clear.ready(helper.getLevel()), "a parcel nobody ordered is found");
            Outcome cleared = Commit.run(helper.getLevel(), clear, fixture.worker);
            helper.assertTrue(cleared instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 10,
                "the stray is carried off whole");
            helper.assertTrue(!clear.ready(helper.getLevel()) && fake.arrived.get(0) == 4,
                "a parcel a collection waits on is never taken as a stray");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aBusyNetworkDefersTheOrderInsteadOfRefusingIt(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        fake.busy = true;
        PackageNetworks.install(fake.adapter());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            CollectNode collect = new CollectNode(fixture.source, Stances.at(fixture.source), iron,
                4, 1, 20, Optional.of(UUID.randomUUID()), "home", new Eta(), nodes);
            nodes.put(collect.id(), collect);
            helper.assertTrue(collect.planned(helper.getLevel()).isEmpty()
                    && collect.refusal(helper.getLevel()).isEmpty(),
                "a packager with a full queue is no reason to fail the collection");
            helper.assertTrue(fake.sent.isEmpty() && !collect.placed() && collect.deferred()
                    && !collect.ready(helper.getLevel()),
                "nothing is ordered while the network is busy");
            collect.retry(helper.getLevel());
            helper.assertTrue(fake.sent.isEmpty(), "a retry waits for the queue to drain");
            fake.busy = false;
            collect.retry(helper.getLevel());
            helper.assertTrue(fake.sent.equals(List.of(4L)) && collect.placed() && !collect.deferred(),
                "the deferred order is placed once the network has room");
            collect.retry(helper.getLevel());
            helper.assertTrue(fake.sent.size() == 1, "a placed order is never placed again");
            fake.arrived.put(0, 4);
            helper.assertTrue(collect.ready(helper.getLevel())
                    && Commit.run(helper.getLevel(), collect, fixture.worker) instanceof Outcome.Done,
                "the deferred order is collected when it arrives");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anOrderWhoseWorkWasDroppedIsAdoptedNotOrderedAgain(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            UUID network = UUID.randomUUID();
            Depot depot = depot(fixture, nodes, network, List.of(), fake.supply());
            Produce six = Produce.of(ResourceLocation.withDefaultNamespace("test"),
                new WorkSite.AtBlock(fixture.source), iron, 6, Produce.UNBOUNDED);
            Refinement.Change first = depot.options(six, Grown.of(six)).getFirst();
            helper.assertTrue(first.holds().stream().allMatch(hold -> hold.take().isEmpty()), "the first is promised");
            CollectNode sent = (CollectNode) first.replacement().nodes().getFirst();
            helper.assertTrue(sent.planned(helper.getLevel()).isEmpty() && fake.sent.equals(List.of(6L)),
                "the first collection orders");
            first.holds().forEach(hold -> hold.release(Ending.REVOKED));
            fake.arrived.put(0, 6);
            PackageNetwork.Parcel parcel = fake.parcels().getFirst();
            helper.assertTrue(nodes.get(sent.id()) == sent && sent.adrift()
                    && !StrayNode.stray(nodes, fixture.source, parcel),
                "a dropped collection's order stays spoken for instead of turning stray");
            Refinement.Change second = depot.options(six, Grown.of(six)).getFirst();
            CollectNode adopter = (CollectNode) second.replacement().nodes().getFirst();
            helper.assertTrue(second.holds().stream().allMatch(hold -> hold.take().isEmpty())
                    && !nodes.containsKey(sent.id()) && nodes.get(adopter.id()) == adopter,
                "the next collection adopts the order in place of the dropped one");
            helper.assertTrue(adopter.planned(helper.getLevel()).isEmpty() && fake.sent.equals(List.of(6L)),
                "adopting an order never orders again");
            helper.assertTrue(adopter.ready(helper.getLevel())
                    && Commit.run(helper.getLevel(), adopter, fixture.worker) instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 6 && nodes.isEmpty(),
                "the adopted parcel is collected whole");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void anUnadoptedOrderTurnsStrayInTime(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            CollectNode collect = new CollectNode(fixture.source, Stances.at(fixture.source), iron,
                4, 1, 20, Optional.of(UUID.randomUUID()), "home", new Eta(), nodes);
            nodes.put(collect.id(), collect);
            helper.assertTrue(collect.planned(helper.getLevel()).isEmpty() && !collect.expired(0),
                "a collection in work never expires");
            collect.setAdrift();
            helper.assertTrue(!collect.expired(100) && !collect.expired(100 + CollectNode.ADRIFT_TICKS),
                "an adrift order waits to be adopted");
            helper.assertTrue(collect.expired(101 + CollectNode.ADRIFT_TICKS),
                "an order nobody adopts is let go");
            helper.succeed();
        } finally {
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void createParcelsAreTakenWholeByTheirOrder(GameTestHelper helper) {
        BlockPos port = new BlockPos(1, 1, 1);
        helper.setBlock(port, BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "package_frogport")));
        BlockPos at = helper.absolutePos(port);
        PackagePortBlockEntity frogport = (PackagePortBlockEntity) helper.getLevel().getBlockEntity(at);
        ItemStack ordered = PackageItem.containing(List.of(new ItemStack(Items.OAK_LOG, 16)));
        PackageItem.setOrder(ordered, 7, 0, true, 0, true, PackageOrderWithCrafts.empty());
        ItemStack byHand = PackageItem.containing(List.of(new ItemStack(Items.OAK_LOG, 5)));
        frogport.inventory.setStackInSlot(0, byHand);
        frogport.inventory.setStackInSlot(1, ordered);
        PackageNetwork network = new CreatePackageNetwork();
        helper.assertTrue(network.parcelsAt(helper.getLevel(), at).stream().map(PackageNetwork.Parcel::order).toList()
                .equals(List.of(OptionalInt.empty(), OptionalInt.of(7))),
            "a parcel reads back the order it was packed for");
        List<ItemStack> taken = network.collectFrom(helper.getLevel(), at, 7);
        helper.assertTrue(taken.stream().mapToInt(ItemStack::getCount).sum() == 16,
            "a collection takes its order's parcel whole");
        helper.assertTrue(network.parcelsAt(helper.getLevel(), at).size() == 1
                && network.collectFrom(helper.getLevel(), at, 7).isEmpty(),
            "a parcel sent by hand is no order's");
        Optional<List<ItemStack>> cleared = network.clearOne(helper.getLevel(), at, parcel -> parcel.order().isEmpty());
        helper.assertTrue(cleared.isPresent() && cleared.get().getFirst().getCount() == 5
                && network.parcelsAt(helper.getLevel(), at).isEmpty(),
            "a stray is cleared whole");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void takesAndOrdersAreCountedOnOneTally(GameTestHelper helper) {
        PlanningFixture fixture = new PlanningFixture(helper);
        ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
        Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
        UUID network = UUID.randomUUID();
        Depot depot = depot(fixture, nodes, network, List.of(),
            List.of(new PackageNetwork.Supply(new ItemStack(Items.IRON_INGOT), 64)), true, 64);
        Produce asked = Produce.of(ResourceLocation.withDefaultNamespace("test"),
            new WorkSite.AtBlock(fixture.target), iron, 48, Produce.UNBOUNDED);
        List<Refinement.Change> offered = depot.options(asked, Grown.of(asked));
        helper.assertTrue(offered.size() == 2
                && offered.get(0).replacement().nodes().getFirst() instanceof TakeNode take && take.count() == 48
                && offered.get(1).replacement().nodes().getFirst() instanceof CollectNode order && order.count() == 48,
            "a kept network offers its goods both by hand and by order");
        helper.assertTrue(offered.get(0).holds().stream().allMatch(hold -> hold.take().isEmpty()),
            "the take is promised");
        List<Refinement.Change> rest = depot.options(asked, Grown.of(asked));
        helper.assertTrue(rest.size() == 2 && rest.stream().allMatch(change ->
                change.replacement().nodes().getFirst().spec().gives().getFirst().count() == 16),
            "what a take promised is gone from what an order may promise");
        helper.assertTrue(rest.get(1).holds().stream().allMatch(hold -> hold.take().isEmpty())
                && depot.options(asked, Grown.of(asked)).isEmpty(),
            "an order and a take together spend the network's stock once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aNetworkContainerIsTakenFromByHandWithNoOneAtTheDesk(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            Depot depot = depot(fixture, nodes, UUID.randomUUID(), List.of(), fake.supply(), false, fake.stock);
            Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), iron, 32);
            var solved = solver.solve(fixture.situation(new Stock(List.of(), Map.of(new Stash(fixture.target), 2)),
                List.of(depot)), fixture.crew, delivery.goals(), List.of(), 0);
            ColonyLabor labor = new ColonyLabor(new ColonyData(), helper.getLevel().dimension());
            PlanningFixture.apply(labor, helper.getLevel(), solved);
            TakeNode take = solved.weave().vertices().values().stream()
                .filter(vertex -> vertex.node() instanceof TakeNode).map(vertex -> (TakeNode) vertex.node())
                .findFirst().orElse(null);
            helper.assertTrue(take != null && take.count() == 32 && fake.sent.isEmpty(),
                "with no one at the desk the goods are fetched by hand, and nothing is ordered");
            helper.assertTrue(Commit.run(helper.getLevel(), take, fixture.worker) instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 32 && fake.stock == 32,
                "the take lifts its goods out of the container");
            fake.stock = 0;
            TakeNode again = new TakeNode(fixture.source, Stances.at(fixture.source), iron, 8, 1, UUID.randomUUID());
            helper.assertTrue(Commit.run(helper.getLevel(), again, fixture.worker) instanceof Outcome.Failed,
                "a container emptied by the network fails the take, so the plan looks again");
            helper.succeed();
        } finally {
            solver.closed();
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aFrogportOverAContainerIsNoPickUpPoint(GameTestHelper helper) {
        ResourceLocation frogport = ResourceLocation.fromNamespaceAndPath("create", "package_frogport");
        ResourceLocation postbox = ResourceLocation.fromNamespaceAndPath("create", "white_postbox");
        ResourceLocation packager = ResourceLocation.fromNamespaceAndPath("create", "packager");
        helper.setBlock(new BlockPos(1, 2, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(3, 2, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.BARREL);
        helper.setBlock(new BlockPos(5, 3, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(5, 2, 1), BuiltInRegistries.BLOCK.get(packager).defaultBlockState()
            .setValue(com.simibubi.create.content.logistics.packager.PackagerBlock.FACING,
                net.minecraft.core.Direction.UP));
        helper.setBlock(new BlockPos(7, 3, 1), BuiltInRegistries.BLOCK.get(frogport));
        helper.setBlock(new BlockPos(7, 2, 1), BuiltInRegistries.BLOCK.get(packager).defaultBlockState()
            .setValue(com.simibubi.create.content.logistics.packager.PackagerBlock.FACING,
                net.minecraft.core.Direction.UP));
        helper.setBlock(new BlockPos(7, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(9, 2, 1), BuiltInRegistries.BLOCK.get(postbox));
        helper.setBlock(new BlockPos(9, 1, 1), Blocks.BARREL);
        var level = helper.getLevel();
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(1, 2, 1))),
            "a frogport over nothing keeps what it catches");
        helper.assertTrue(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(3, 2, 1))),
            "a frogport over a container pushes its parcels into it");
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(5, 3, 1))),
            "a packager facing no container has nowhere to unpack");
        helper.assertTrue(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(7, 3, 1))),
            "a packager facing a container unpacks into it");
        helper.assertFalse(CreateLayout.unpacks(level, helper.absolutePos(new BlockPos(9, 2, 1))),
            "a postbox keeps what it is sent");
        helper.assertTrue(CreateLayout.targetOf(level, helper.absolutePos(new BlockPos(7, 2, 1)))
                .equals(Optional.of(helper.absolutePos(new BlockPos(7, 1, 1)))),
            "a packager's container is the one behind the way it faces");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theContainerNearestTheCrewIsTakenFromAndTakenFromAgainWhenTheyMove(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            UUID network = UUID.randomUUID();
            // One container behind the hand, further from the delivery than another on the far side of it.
            var near = fixture.source.at(fixture.source.cell().offset(-30, 0, 0));
            var far = fixture.source.at(fixture.source.cell().offset(30, 0, 0));
            Depot depot = new Depot(UUID.randomUUID(), new WorkSite.AtBlock(near),
                new Tally(network, List.of(new PackageNetwork.Supply(new ItemStack(Items.IRON_INGOT), 128)), 0, false,
                    nodes, new LinkedHashMap<>()), List.of(),
                List.of(new Depot.Shelf(near, Stances.at(near), Map.of(Goods.specOf(new ItemStack(Items.IRON_INGOT)), 64L)),
                    new Depot.Shelf(far, Stances.at(far), Map.of(Goods.specOf(new ItemStack(Items.IRON_INGOT)), 64L))),
                new Eta(), 1);
            Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), iron, 32);
            var situation = fixture.situation(new Stock(List.of(), Map.of(new Stash(fixture.target), 2)),
                List.of(depot));
            var hand = fixture.crew.hands().getFirst();
            var there = new Crew(List.of(new Crew.Hand(hand.who(), near, hand.packCells(), hand.packCapacity(),
                hand.pace(), hand.licences(), hand.tools(), Optional.empty())));
            var solved = solver.solve(situation, there, delivery.goals(), List.of(), 0);
            helper.assertTrue(taken(solved).equals(List.of(near)),
                "the hand takes from the container it stands by, though another is nearer the delivery");
            var moved = new Crew(List.of(new Crew.Hand(hand.who(), far, hand.packCells(), hand.packCapacity(),
                hand.pace(), hand.licences(), hand.tools(), Optional.empty())));
            solved = solver.solve(situation, moved, delivery.goals(), List.of(), 20);
            helper.assertTrue(taken(solved).equals(List.of(far)),
                "once the hand stands by the other container, the take moves there before it is begun");
            solved = solver.solve(situation, moved, delivery.goals(), List.of(), 40);
            helper.assertTrue(taken(solved).equals(List.of(far)) && depot.tally().taking().size() == 1,
                "the take stays put while the hand does, and is promised once");
            helper.succeed();
        } finally {
            solver.closed();
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void goodsAreSentByHandWhenThatBeatsCarryingThem(GameTestHelper helper) {
        PackageNetwork original = PackageNetworks.get();
        Network fake = new Network();
        PackageNetworks.install(fake.adapter());
        PlanningFixture.Rounds solver = new PlanningFixture.Rounds(new Claims(), new Cooldowns());
        try {
            PlanningFixture fixture = new PlanningFixture(helper);
            ItemSpec iron = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));
            Map<UUID, CollectNode> nodes = new LinkedHashMap<>();
            // A packager and its port beside the store the iron lies in, and a pick-up point at the far store.
            var packager = fixture.source.at(fixture.source.cell().offset(0, 0, 1));
            var port = fixture.source.at(fixture.source.cell().offset(0, 1, 1));
            NetworkSnapshot.Port there = new NetworkSnapshot.Port("far", true, List.of(), Optional.empty(), 0);
            Counter counter = new Counter(UUID.randomUUID(), new WorkSite.AtBlock(packager),
                List.of(new Counter.Desk(packager, Stances.at(packager), port,
                    List.of(new Depot.Dock(fixture.target, Stances.at(fixture.target), there, 0, true)))),
                nodes, new Eta(), (level, from, to, parcel, collect) -> fake.adapter().send(level, from.block(level),
                    parcel), 1);
            Owed delivery = new Owed(new WorkSite.AtBlock(fixture.target), Stances.at(fixture.target), iron, 32);
            Stock stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.source),
                new ItemStack(Items.IRON_INGOT, 64))), Map.of(new Stash(fixture.target), 2, new Stash(fixture.source), 0));
            var solved = solver.solve(fixture.situation(stock, List.of(counter)), fixture.crew, delivery.goals(),
                List.of(), 0);
            ColonyLabor labor = new ColonyLabor(new ColonyData(), helper.getLevel().dimension());
            PlanningFixture.apply(labor, helper.getLevel(), solved);
            PackNode pack = solved.weave().vertices().values().stream()
                .filter(vertex -> vertex.node() instanceof PackNode).map(vertex -> (PackNode) vertex.node())
                .findFirst().orElse(null);
            helper.assertTrue(pack != null && pack.collection().byHand() && nodes.containsKey(pack.collection().id()),
                "the iron is packed by the store and sent, not walked to the far store");
            helper.assertTrue(fake.sent.isEmpty() && !pack.collection().placed(),
                "nothing is asked of any network, and nothing is waited on before it is sent");
            Containers.insert(fixture.worker.pack(), new ItemStack(Items.IRON_INGOT, 32));
            helper.assertTrue(Commit.run(helper.getLevel(), pack, fixture.worker) instanceof Outcome.Done
                    && fake.posted.size() == 1 && pack.collection().placed(),
                "the packed parcel leaves from the port and is waited on from then");
            CollectNode collect = pack.collection();
            fake.arrived.put(collect.handOrder(), 32);
            helper.assertTrue(collect.ready(helper.getLevel())
                    && Commit.run(helper.getLevel(), collect, fixture.worker) instanceof Outcome.Done done
                    && done.made().stream().mapToInt(ItemStack::getCount).sum() == 32,
                "the parcel is collected whole at the far end by its own number");
            helper.succeed();
        } finally {
            solver.closed();
            PackageNetworks.install(original);
        }
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void parcelsOnTheBooksOutlastASave(GameTestHelper helper) {
        var level = helper.getLevel();
        Mailroom kept = new Mailroom();
        Mailroom.Consignment one = new Mailroom.Consignment(UUID.randomUUID(), -7, UUID.randomUUID(),
            io.github.izakyl.folkways.core.api.terms.WorldPos.of(level, new BlockPos(1, 2, 3)),
            io.github.izakyl.folkways.core.api.terms.WorldPos.of(level, new BlockPos(4, 5, 6)),
            Optional.of(UUID.randomUUID()), "Alpha");
        kept.take(one);
        Mailroom loaded = new Mailroom();
        loaded.load(io.github.izakyl.folkways.base.persist.Reader.of(kept.save()));
        helper.assertTrue(loaded.all().equals(List.of(one)), "a booking is read back as it was kept");
        Mailroom.Consignment unbooked = one.booked(Optional.empty());
        helper.assertTrue(unbooked.train().isEmpty() && !unbooked.boards(one.train().get(), "Alpha")
                && one.boards(one.train().get(), "Alpha") && !one.boards(one.train().get(), "Beta"),
            "a parcel goes aboard only the train it is booked on, at the station it was booked from");
        helper.assertTrue(loaded.holds(one.from(), -7) && !loaded.holds(one.to(), -7) && loaded.ofOrder(-7).size() == 1,
            "the books find a parcel by its order and the postbox it waits in");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theHooksIntoTrainsAndStationsTakeHold(GameTestHelper helper) {
        try {
            // Loading them applies the colony's hooks: one that no longer fits fails the load.
            for (String hooked : List.of("com.simibubi.create.content.trains.entity.Train",
                    "com.simibubi.create.content.trains.station.GlobalStation")) {
                Class.forName(hooked, true, DispatchPlanningGameTests.class.getClassLoader());
            }
        } catch (ClassNotFoundException missing) {
            throw new AssertionError(missing);
        }
        helper.setBlock(new BlockPos(1, 1, 1),
            BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("create", "track_station")));
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(1, 1, 1)))
                instanceof com.simibubi.create.content.trains.station.StationBlockEntity,
            "a station loads with the colony's hook into how it keeps its postboxes' addresses");
        helper.succeed();
    }

    private static List<io.github.izakyl.folkways.core.api.terms.WorldPos> taken(io.github.izakyl.folkways.core.engine.plan.Planned solved) {
        return solved.weave().vertices().values().stream()
            .filter(vertex -> vertex.node() instanceof TakeNode)
            .map(vertex -> ((TakeNode) vertex.node()).container()).toList();
    }

    // A network with one pick-up point at the fixture's source and a desk kept, as the depot reads it.
    private static Depot depot(PlanningFixture fixture, Map<UUID, CollectNode> nodes, UUID network,
                               List<PackageNetwork.Parcel> parcels, List<PackageNetwork.Supply> supply) {
        NetworkSnapshot.Port port = new NetworkSnapshot.Port("home", true, parcels, Optional.of(network), 20);
        return new Depot(UUID.randomUUID(), new WorkSite.AtBlock(fixture.source),
            new Tally(network, supply, 0, true, nodes, new LinkedHashMap<>()),
            List.of(new Depot.Dock(fixture.source, Stances.at(fixture.source), port, 20, true)), List.of(),
            new Eta(), 1);
    }

    // The same network with a container at the fixture's source holding `shelved` iron, kept or not.
    private static Depot depot(PlanningFixture fixture, Map<UUID, CollectNode> nodes, UUID network,
                               List<PackageNetwork.Parcel> parcels, List<PackageNetwork.Supply> supply,
                               boolean manned, long shelved) {
        Depot plain = depot(fixture, nodes, network, parcels, supply);
        return new Depot(plain.key(), plain.site(),
            new Tally(network, supply, 0, manned, nodes, new LinkedHashMap<>()), plain.docks(),
            List.of(new Depot.Shelf(fixture.source, Stances.at(fixture.source), Map.of(Goods.specOf(new ItemStack(Items.IRON_INGOT)), shelved))),
            plain.eta(), plain.reachTicks());
    }

    private static final class Network {
        long stock = 64;
        boolean busy;
        final Map<Integer, Integer> arrived = new LinkedHashMap<>();
        final List<PackageNetwork.Parcel> strays = new ArrayList<>();
        final List<Long> sent = new ArrayList<>();
        final List<ItemStack> posted = new ArrayList<>();

        List<PackageNetwork.Parcel> parcels() {
            List<PackageNetwork.Parcel> here = new ArrayList<>(strays);
            arrived.forEach((order, count) -> here.add(
                new PackageNetwork.Parcel(List.of(new ItemStack(Items.IRON_INGOT, count)), OptionalInt.of(order))));
            return here;
        }

        List<PackageNetwork.Supply> supply() {
            return List.of(new PackageNetwork.Supply(new ItemStack(Items.IRON_INGOT), stock));
        }

        PackageNetwork adapter() {
            return (PackageNetwork) Proxy.newProxyInstance(PackageNetwork.class.getClassLoader(),
                new Class<?>[] {PackageNetwork.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isPort", "portOpen" -> true;
                    case "busy" -> busy;
                    case "summaryOf" -> supply();
                    case "mails" -> false;
                    case "pack" -> Optional.of(new ItemStack(Items.PAPER));
                    case "send" -> posted.add((ItemStack) args[2]);
                    case "takeOut" -> {
                        long count = (long) args[3];
                        if (count > stock) {
                            yield List.of();
                        }
                        stock -= count;
                        yield List.of(new ItemStack(Items.IRON_INGOT, (int) count));
                    }
                    case "parcelsAt" -> parcels();
                    case "collectFrom" -> {
                        Integer count = arrived.remove((int) args[2]);
                        yield count == null ? List.of() : List.of(new ItemStack(Items.IRON_INGOT, count));
                    }
                    case "clearOne" -> {
                        @SuppressWarnings("unchecked")
                        Predicate<PackageNetwork.Parcel> stray = (Predicate<PackageNetwork.Parcel>) args[2];
                        Optional<PackageNetwork.Parcel> found = parcels().stream().filter(stray).findFirst();
                        found.ifPresent(parcel -> {
                            strays.remove(parcel);
                            parcel.order().ifPresent(arrived::remove);
                        });
                        yield found.map(PackageNetwork.Parcel::contents);
                    }
                    case "request" -> {
                        @SuppressWarnings("unchecked")
                        List<PackageNetwork.Supply> requested = (List<PackageNetwork.Supply>) args[3];
                        sent.add(requested.stream().mapToLong(PackageNetwork.Supply::count).sum());
                        yield OptionalInt.of(sent.size() - 1);
                    }
                    default -> throw new AssertionError("unexpected network call: " + method.getName());
                });
        }
    }
}
