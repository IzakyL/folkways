package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.transport.BatchRequest;
import io.github.izakyl.folkways.core.api.transport.LayResult;
import io.github.izakyl.folkways.core.api.transport.QuoteResult;
import io.github.izakyl.folkways.core.api.transport.TransportEndpoint;
import io.github.izakyl.folkways.core.api.transport.TransportLaying;
import io.github.izakyl.folkways.core.api.transport.TransportLink;
import io.github.izakyl.folkways.core.api.transport.TransportMode;
import io.github.izakyl.folkways.core.api.transport.TransportPublication;
import io.github.izakyl.folkways.core.api.transport.TransportQuery;
import io.github.izakyl.folkways.core.api.transport.TransportQuote;
import io.github.izakyl.folkways.core.api.transport.TransportSnapshot;
import io.github.izakyl.folkways.core.api.transport.TransportTravel;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Sizing;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.TransportIntent;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.haul.HaulRefusal;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.core.engine.travel.Faring;
import io.github.izakyl.folkways.core.engine.travel.Journey;
import io.github.izakyl.folkways.core.engine.travel.Ways;
import io.github.izakyl.folkways.core.testing.Nodes;
import io.github.izakyl.folkways.effect.Effects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
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
public final class TransportExecutionGameTests {

    private static final ResourceLocation MODE = ResourceLocation.fromNamespaceAndPath("folkways", "execution_test");
    private static final ItemSpec GOODS = ItemSpec.exact(new ItemStack(Items.IRON_INGOT));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TransportExecutionGameTests.class);
    }

    @GameTest(template = "empty")
    public static void planningManyStockLots(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            for (int store = 0; store < 4; store++) {
                for (int slot = 0; slot < 20; slot++) {
                    rig.store(store).setItem(slot, new ItemStack(Items.IRON_INGOT, 64));
                }
            }
            List<Crew.Hand> hands = new ArrayList<>(rig.crew.hands());
            Crew.Hand hand = hands.getFirst();
            for (int i = 0; i < 4; i++) {
                hands.add(new Crew.Hand(new Resident(UUID.randomUUID(), hand.who().kind(), hand.who().routeKind()),
                    rig.pos(i), 6, 6, 1, hand.licences(), List.of(), Optional.empty()));
            }
            rig.crew = new Crew(hands);
            for (int from = 0; from < 4; from++) {
                for (int to = from + 1; to < 5; to++) {
                    rig.mode.line("bench_" + from + "_" + to, from, to, 5);
                }
            }
            for (int i = 0; i < 60; i++) {
                Container store = new SimpleContainer(1);
                store.setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                rig.stores.put(rig.pos(0).at(rig.pos(0).cell().north(i + 1)), store);
            }
            long began = System.nanoTime();
            rig.weaver.know(rig.situation(), rig.crew);
            for (int i = 0; i < 8; i++) {
                Node consumer = Nodes.done(NodeSpec.of(UUID.randomUUID(), MODE,
                    new WorkSite.AtBlock(rig.pos(4)), Stances.at(rig.pos(4)), Workload.Once.of(1))
                    .needs(new Need(GOODS, 4, true)).done());
                rig.weaver.submit(MODE, Grown.of(consumer));
            }
            rig.view.apply(rig.weaver.grow(false));
            long elapsed = System.nanoTime() - began;
            helper.assertTrue(rig.view.vertices().size() == 32 && rig.weaver.shortfalls().isEmpty(),
                "eight deliveries keep the same complete four-node routes among many stock lots");
            helper.assertTrue(rig.publications == 1, "one planning round captures the publication once");
            System.out.println("TRANSPORT_BENCH travel=" + rig.estimates + " mode=" + rig.mode.quoted
                + " nanos=" + elapsed + " nodes=" + rig.view.vertices().size());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void foreignTransportKeepsPackAndStockPlanningUnrouted(GameTestHelper helper) {
        for (int publication = 0; publication < 3; publication++) {
            try (Rig rig = new Rig(helper)) {
                rig.published = publication != 0;
                rig.foreign = publication == 1;
                rig.forbidEstimates = publication != 2;
                rig.mode.line("trunk", 1, 3, 1);
                rig.plan();
                helper.assertTrue(publication == 2 ? rig.mode.laid > 0 : rig.mode.discovered == 0,
                    "only a local publication routes and prices stock through transport modes");
            }
            try (Rig rig = new Rig(helper)) {
                rig.published = publication != 0;
                rig.foreign = publication == 1;
                rig.forbidEstimates = publication != 2;
                rig.carried();
                Crew.Hand hand = rig.crew.hands().getFirst();
                rig.owed = new PlanningFixture.Owed(new WorkSite.AtEntity(hand.id(), rig.pos(0)),
                    Stances.WHEREVER, GOODS, 4);
                rig.weaver.know(rig.situation(), rig.crew);
                rig.owed.goals().forEach(work -> rig.weaver.submit(MODE, work));
                rig.view.apply(rig.weaver.grow(false));
                helper.assertTrue(publication == 2 ? rig.weaver.shortfalls().isEmpty()
                        : !rig.weaver.shortfalls().isEmpty(),
                    "foreign transport keeps Receipt from counting existing pack supplies as new goods");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void transportIntentsEstimateThroughTheRegistryWithoutLaying(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            Routing routing = new Routing();
            Refinement.Context context = new Refinement.Context() {
                public Sizing sizing() {
                    return Sizing.UNBOUNDED;
                }

                public Optional<Refinement.Planning> planning() {
                    return Optional.of(new Refinement.Planning() {
                        public long now() {
                            return 500;
                        }

                        public TransportPublication transports() {
                            return rig.publication();
                        }

                        public TransportSnapshot snapshot(ItemSpec goods) {
                            List<TransportSnapshot.Carrier> carriers = rig.crew.hands().stream()
                                .map(hand -> DirectTransport.carrier(hand.who(), hand.at(), goods, 64, 1)).toList();
                            return new TransportSnapshot(1, now(), rig.situation().ways().estimates(), carriers);
                        }
                    });
                }
            };
            var registry = new Refining(List.of(routing), () -> context);
            TransportIntent intent = TransportIntent.of(rig.mode.endpoint(0), rig.mode.endpoint(4), GOODS, 4, 500);
            rig.mode.line("trunk", 1, 3, 1);
            long estimate = registry.estimate(intent).orElseThrow();
            helper.assertTrue(estimate > 0 && estimate < 20L * rig.pos(0).cell().distManhattan(rig.pos(4).cell()),
                "the registry prices the transport intent by its cheapest complete route");
            helper.assertTrue(!rig.mode.departures.isEmpty()
                    && rig.mode.departures.stream().allMatch(departure -> departure >= intent.readyAt()),
                "every quote starts when this intent's goods can reach that leg");
            helper.assertTrue(rig.mode.laid == 0 && rig.claims.size() == 0 && rig.view.vertices().isEmpty(),
                "estimating an intent neither lays work nor reserves its materials");
            int quoted = rig.mode.quoted;
            helper.assertTrue(registry.estimate(TransportIntent.of(intent.from(), intent.to(), GOODS, 4, 500))
                    .orElseThrow() == estimate && rig.mode.quoted == quoted,
                "the same request with a fresh intent id is priced once this round");
            rig.unknownAll = true;
            routing.begin(rig.publication());
            helper.assertTrue(registry.estimate(intent).isEmpty(), "unknown travel leaves the intent's cost pending");
            rig.unknownAll = false;
            rig.cutDirect = true;
            rig.mode.lines.clear();
            routing.begin(rig.publication());
            helper.assertTrue(registry.estimate(intent).orElseThrow() == Long.MAX_VALUE,
                "a known refusal differs from a pending estimate");
            helper.assertTrue(new Refining(List.of(routing)).estimate(intent).isEmpty(),
                "contexts without planning keep the existing default contract");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void workshopOutputsArePricedFromWorkerStances(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            WorldPos station = rig.pos(2).at(rig.pos(2).cell().offset(0, 0, 2));
            WorldPos footing = station.at(station.cell().offset(1, 0, 0));
            rig.blocked = station;
            rig.store(0).setItem(0, new ItemStack(Items.RAW_IRON, 4));
            rig.workshops = List.of(new Workshop() {
                private final UUID key = UUID.randomUUID();

                public UUID key() { return key; }

                public ResourceLocation id() { return MODE; }

                public WorkSite site() { return new WorkSite.AtBlock(station); }

                public List<Refinement.Change> options(Intent intent, Grown graph) {
                    if (!(intent instanceof Produce produce) || !Goods.overlap(produce.wanted(), GOODS)) {
                        return List.of();
                    }
                    NodeSpec spec = NodeSpec.of(UUID.randomUUID(), MODE, site(), Stances.at(footing),
                        Workload.Once.of(1)).needs(new Need(ItemSpec.of(Items.RAW_IRON), 4, true))
                        .gives(new Amount(GOODS, 4)).done();
                    Node make = new Node() {
                        public NodeSpec spec() { return spec; }

                        public Outcome commit(ServerLevel level, Worker who) {
                            helper.assertTrue(who.supplied().stream().mapToInt(ItemStack::getCount).sum() == 4,
                                "the workshop consumes the actual raw iron supplied by the plan");
                            return new Outcome.Done(List.of(), List.of(new ItemStack(Items.IRON_INGOT, 4)),
                                Optional.empty());
                        }
                    };
                    return List.of(Refinement.Change.of(Grown.of(make)));
                }
            });
            rig.plan();
            helper.assertTrue(rig.weaver.shortfalls().isEmpty(),
                "a solid workstation is not a loading stance for its output: " + rig.weaver.shortfalls());
            rig.run();
            helper.assertTrue(rig.owed.complete() && Goods.countIn(rig.store(4), GOODS) == 4,
                "with transport published, the workshop's real output still reaches its consumer");
            helper.assertTrue(rig.stores.values().stream()
                    .mapToLong(store -> Goods.countIn(store, ItemSpec.of(Items.RAW_IRON))).sum() == 0,
                "production consumes the source stock instead of inventing finished goods");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void carryingAroundATrunkReallyDeliversAndConservesGoods(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4 && rig.mode.sent == 1,
                "the selected trunk and both carries deliver the actual batch exactly once");
            helper.assertTrue(Goods.countIn(rig.store(4), GOODS) == 4, "all four items arrive in the final store");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.carried();
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4 && rig.mode.sent == 1,
                "goods already filling a pack still use the same complete transport entry");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.carried();
            rig.owed = new PlanningFixture.Owed(new WorkSite.AtEntity(rig.other.worker.resident().id(), rig.pos(4)),
                Stances.at(rig.pos(4)), GOODS, 4);
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4 && rig.mode.sent == 1
                && Goods.countIn(rig.other.worker.pack(), GOODS) == 4,
                "the same route carries another resident's goods to the specified receiving resident");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void linesOfOneModeAreNeverChained(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("first", 1, 2, 1).line("second", 2, 3, 1);
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 1 && rig.mode.laid == 1 && rig.total() == 4,
                "the batch rides one line and is carried the rest of the way");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sourcesArePricedByTheRouteTheyWouldTake(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.store(0).clearContent();
            rig.store(1).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            rig.store(2).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            rig.mode.line("trunk", 1, 3, 1);
            rig.plan();
            helper.assertTrue(rig.claims.taken(new Resource.Lot(new Stash(rig.pos(1)), GOODS)) == 4
                    && rig.claims.taken(new Resource.Lot(new Stash(rig.pos(2)), GOODS)) == 0 && rig.mode.laid == 1,
                "the farther store beside a cheap trunk beats the store nearer by straight line");
            helper.assertTrue(rig.view.vertices().values().stream().noneMatch(vertex ->
                    vertex.node() instanceof io.github.izakyl.folkways.core.engine.plan.haul.TransferNode
                        && vertex.node().spec().site().where().equals(rig.pos(1))
                        && !vertex.node().spec().gives().isEmpty()),
                "goods already in the trunk's own container are not taken out and put back");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 1 && rig.total() == 8
                && Goods.countIn(rig.store(2), GOODS) == 4, "the goods really go by the trunk");
        }
        try (Rig rig = new Rig(helper)) {
            rig.store(0).clearContent();
            rig.store(1).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            rig.store(2).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            rig.published = false;
            rig.plan();
            helper.assertTrue(rig.claims.taken(new Resource.Lot(new Stash(rig.pos(2)), GOODS)) == 4,
                "without a published transport the nearer store still wins by straight line");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void downstreamWaitsAndFailedUpstreamCreatesNoOutput(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.mode.arrived = false;
            rig.plan();
            rig.run();
            helper.assertTrue(!rig.owed.complete() && rig.mode.sent == 1 && rig.store(3).isEmpty(),
                "a sent batch stays in custody until its collection is ready");
            helper.assertTrue(rig.total() == 4 && rig.store(4).isEmpty(), "no downstream output appears early");
            rig.mode.arrived = true;
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4, "the waiting chain resumes when goods arrive");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.mode.failSend = true;
            rig.plan();
            rig.run();
            helper.assertTrue(!rig.owed.complete() && rig.store(3).isEmpty() && rig.store(4).isEmpty()
                && rig.mode.sent == 0 && rig.total() == 4, "failed sending restores inputs and produces no goods");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aDearTrunkLosesToExecutableDirectCarrying(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("dear", 1, 3, 100_000);
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.laid == 0 && rig.total() == 4,
                "the complete direct option wins and executes without a trunk");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void failedExpansionReleasesTheWholeGrowthAndUsesTheNextLine(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("rejected", 1, 3, 1).line("accepted", 1, 3, 2);
            rig.mode.rejectFirst = true;
            rig.plan();
            helper.assertTrue(rig.mode.taken == 2 && rig.mode.released == 1,
                "failed expansion releases its hold before another candidate reserves the same capacity");
            helper.assertTrue(rig.claims.taken(new Resource.Lot(new Stash(rig.pos(0)), GOODS)) == 4,
                "the rejected path leaves no duplicate source claims");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4 && rig.mode.sent == 1
                && rig.mode.released == 2, "only the accepted route executes and its hold also settles once");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("rejected", 1, 3, 1).line("accepted", 1, 3, 2);
            rig.mode.invalidateFirst = true;
            rig.plan();
            helper.assertTrue(rig.revision == 2 && rig.mode.released == 1,
                "publication invalidation during expansion rolls back the whole unpublished growth");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.total() == 4 && rig.mode.sent == 1,
                "a fresh quote for the next candidate executes after invalidation");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void noPublishedModeNeverEntersTheEstimate(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.published = false;
            rig.forbidEstimates = true;
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.discovered == 0 && rig.total() == 4,
                "without publication the original carrying path executes without discovering or quoting hubs");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void partialCoverageRequiresACompleteExecutableTail(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.mode.limit = 2;
            rig.cutDirect = true;
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 2 && rig.total() == 4,
                "the ordinary source growth fills two complete two-item routes");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.mode.limit = 2;
            rig.cutDirect = true;
            rig.carried();
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 2 && rig.total() == 4,
                "carried shares can change workers and fill the same demand through complete smaller routes");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("first", 1, 2, 1).line("blocked", 2, 3, 1);
            rig.mode.limit = 2;
            rig.cutDirect = true;
            rig.weaver.know(rig.situation(), rig.crew);
            rig.owed.goals().forEach(work -> rig.weaver.submit(MODE, work));
            rig.view.apply(rig.weaver.grow(false));
            helper.assertTrue(rig.mode.sent == 0 && rig.mode.laid == 0 && rig.store(4).isEmpty()
                && rig.total() == 4, "a partial prefix with no complete tail is never expanded or counted");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void pendingRoutesUseKnownWorkAndRequestUnknownConnections(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.unknownApproach = true;
            rig.plan();
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.requests > 0 && rig.total() == 4,
                "known work executes while unknown alternatives are requested outside quoting");
        }
        try (Rig rig = new Rig(helper)) {
            rig.mode.line("trunk", 1, 3, 1);
            rig.unknownAll = true;
            rig.carried();
            rig.weaver.know(rig.situation(), rig.crew);
            rig.owed.goals().forEach(work -> rig.weaver.submit(MODE, work));
            rig.view.apply(rig.weaver.grow(false));
            helper.assertTrue(rig.requests > 0 && !rig.weaver.shortfalls().isEmpty()
                && rig.weaver.shortfalls().stream().noneMatch(shortfall -> shortfall.why() instanceof Why.NoWay),
                "an unresolved carried batch stays unsatisfied without falsely covering its need or becoming a denial");
            helper.assertTrue(rig.view.vertices().isEmpty() && rig.claims.size() == 0 && rig.total() == 4,
                "a pending carried source leaves neither a phantom growth nor claims");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aModesOwnTransfersCarryDirectlyAndRollBackWithIt(GameTestHelper helper) {
        try (Rig rig = new Rig(helper)) {
            rig.nested();
            rig.plan();
            helper.assertTrue(rig.mode.innerLaid == 0 && rig.mode.childCalls == 1,
                "goods the mode moves between its own nodes are carried, never sent along another trunk");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 1 && rig.total() == 4,
                "the outer trunk and its own carry really move the same four items to the destination");
        }
        try (Rig rig = new Rig(helper)) {
            rig.nested();
            rig.mode.rejectNested = true;
            rig.mode.line("fallback", 1, 3, 2);
            rig.plan();
            helper.assertTrue(rig.mode.innerLaid == 0 && rig.mode.taken == 2 && rig.mode.released == 1,
                "rejecting the outer candidate releases its hold before trying the fallback");
            helper.assertTrue(rig.claims.taken(new Resource.Lot(new Stash(rig.pos(0)), GOODS)) == 4
                && rig.mode.abandoned.stream().noneMatch(rig.view::has),
                "the mode's nodes, flows, source claims and bindings disappear with its growth");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 1 && rig.total() == 4,
                "only the fallback executes after rollback");
            helper.assertTrue(rig.claims.size() == 0 && rig.mode.taken == rig.mode.released,
                "all accepted resources and holds settle exactly once");
        }
        try (Rig rig = new Rig(helper)) {
            rig.nested();
            rig.mode.refuseNested = true;
            rig.mode.line("fallback", 1, 3, 2);
            rig.plan();
            helper.assertTrue(rig.mode.innerLaid == 0 && rig.mode.childCalls == 1,
                "a carry the mode cannot make is not replaced by another trunk");
            rig.run();
            helper.assertTrue(rig.owed.complete() && rig.mode.sent == 1 && rig.total() == 4,
                "the outer candidate fails and the fallback executes");
        }
        try (Rig rig = new Rig(helper)) {
            rig.nested();
            rig.mode.wideNested = true;
            rig.mode.refuseNested = true;
            rig.weaver.know(rig.situation(), rig.crew);
            rig.owed.goals().forEach(work -> rig.weaver.submit(MODE, work));
            rig.view.apply(rig.weaver.grow(false));
            helper.assertTrue(rig.mode.childCalls < 256,
                "failed attempts share the top-level work budget instead of receiving fresh allowances");
            helper.assertTrue(rig.view.vertices().isEmpty() && rig.claims.size() == 0
                && rig.mode.taken == rig.mode.released && rig.total() == 4,
                "budget exhaustion rolls back the whole growth without touching the actual goods");
            helper.assertTrue(rig.weaver.shortfalls().stream().noneMatch(gap -> gap.why() instanceof Why.NoWay),
                "exhausted expansion remains unresolved rather than being reported as unreachable");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void inReachStockFeedsAnUnlicensedWorkerWithOrWithoutTransport(GameTestHelper helper) {
        for (boolean published : List.of(false, true)) {
            try (Rig rig = new Rig(helper)) {
                rig.published = published;
                rig.workers.remove(rig.other.worker);
                Crew.Hand hand = rig.crew.hands().getFirst();
                rig.crew = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 6, 6, 1,
                    List.of(), List.of(), Optional.empty())));
                Node consumer = Nodes.done(NodeSpec.of(UUID.randomUUID(), MODE,
                    new WorkSite.AtBlock(rig.pos(0)), Stances.at(rig.pos(0)), Workload.Once.of(1))
                    .needs(new Need(GOODS, 4)).done());
                rig.weaver.know(rig.situation(), rig.crew);
                rig.weaver.submit(MODE, Grown.of(consumer));
                rig.view.apply(rig.weaver.grow(false));
                helper.assertTrue(rig.weaver.shortfalls().isEmpty()
                        && rig.view.vertices().keySet().equals(Set.of(consumer.id())),
                    "in-reach stock feeds the worker without any carrying work, published=" + published);
                helper.assertTrue(rig.mode.discovered == 0,
                    "in-reach stock is priced at zero without asking for a carrier quote");
                rig.run();
                helper.assertTrue(rig.store(0).isEmpty() && rig.fixture.worker.pack().isEmpty(),
                    "the consumer takes the stock directly from the store");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void directCarryKeepsPackSizedSharesWithUnrelatedTransport(GameTestHelper helper) {
        for (boolean published : List.of(false, true)) {
            try (Rig rig = new Rig(helper)) {
                rig.published = published;
                rig.workers.remove(rig.other.worker);
                rig.store(0).setItem(0, new ItemStack(Items.IRON_INGOT, 64));
                rig.store(0).setItem(1, new ItemStack(Items.IRON_INGOT, 64));
                Crew.Hand hand = rig.crew.hands().getFirst();
                rig.crew = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 1, 6, 1,
                    hand.licences(), List.of(), Optional.empty())));
                Node consumer = Nodes.done(NodeSpec.of(UUID.randomUUID(), MODE,
                    new WorkSite.AtBlock(rig.pos(4)), Stances.at(rig.pos(4)), Workload.Once.of(1))
                    .needs(new Need(GOODS, 128, true)).done());
                rig.weaver.know(rig.situation(), rig.crew);
                rig.weaver.submit(MODE, Grown.of(consumer));
                rig.view.apply(rig.weaver.grow(false));
                helper.assertTrue(rig.weaver.shortfalls().isEmpty() && rig.view.vertices().size() == 3
                        && rig.view.vertices().values().stream()
                            .filter(vertex -> vertex.node() instanceof TransferNode fetch
                                && fetch.amount() == 64).count() == 2,
                    "a fixed 128-item need grows two 64-item fetches, published=" + published);
                rig.run();
                helper.assertTrue(rig.store(0).isEmpty() && rig.fixture.worker.pack().isEmpty(),
                    "both shares reach and are consumed by the same worker");
            }
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        private final GameTestHelper helper;
        private final PlanningFixture fixture;
        private final PlanningFixture other;
        private Crew crew;
        private final List<Worker> workers = new ArrayList<>();
        private final Map<WorldPos, Container> stores = new LinkedHashMap<>();
        private final List<WorldPos> placed;
        private final Claims claims = new Claims();
        private final Weaver weaver = new Weaver(claims, new Cooldowns());
        private final WeaveView view = new WeaveView();
        private final Fake mode = new Fake(this);
        private PlanningFixture.Owed owed;
        private boolean published = true;
        private boolean foreign;
        private boolean forbidEstimates;
        private boolean cutDirect;
        private boolean unknownApproach;
        private boolean unknownAll;
        private WorldPos blocked;
        private List<Workshop> workshops = List.of();
        private final Map<List<WorldPos>, Integer> requested = new LinkedHashMap<>();
        private int requests;
        private int estimates;
        private int publications;
        private long revision = 1;

        private Rig(GameTestHelper helper) {
            this.helper = helper;
            fixture = new PlanningFixture(helper);
            other = new PlanningFixture(helper);
            Crew.Hand second = other.crew.hands().getFirst();
            crew = new Crew(List.of(fixture.crew.hands().getFirst(),
                new Crew.Hand(second.who(), pos(4), 6, 6, 1, second.licences(), List.of(), Optional.empty())));
            workers.addAll(List.of(fixture.worker, other.worker));
            for (int i = 0; i < 5; i++) {
                helper.getLevel().setBlockAndUpdate(pos(i).cell(), Blocks.BARREL.defaultBlockState());
                Container store = Containers.at(helper.getLevel(), pos(i).cell()).orElseThrow();
                store.clearContent();
                stores.put(pos(i), store);
            }
            placed = List.copyOf(stores.keySet());
            store(0).setItem(0, new ItemStack(Items.IRON_INGOT, 4));
            owed = new PlanningFixture.Owed(new WorkSite.AtBlock(pos(4)), Stances.at(pos(4)), GOODS, 4);
        }

        private WorldPos pos(int which) {
            int[] offsets = {0, 8, 28, 52, 60};
            return fixture.source.at(fixture.source.cell().offset(offsets[which], 0, 0));
        }

        private Container store(int which) {
            return stores.get(pos(which));
        }

        private TransportPublication publication() {
            publications++;
            return new TransportPublication(revision, published ? List.of(new TransportPublication.Entry(MODE,
                foreign ? net.minecraft.world.level.Level.NETHER : helper.getLevel().dimension(),
                new TransportPublication.Offering(mode,
                    new TransportSnapshot(revision, 0, TransportTravel.UNKNOWN, List.of()), true))) : List.of());
        }

        private Situation situation() {
            var building = Stands.building();
            Map<Stash, Integer> room = new LinkedHashMap<>();
            List<Stock.Holding> stock = new ArrayList<>();
            stores.forEach((pos, store) -> {
                Stash at = new Stash(pos);
                building.container(at, Set.of(new Stand(pos)));
                int empty = 0;
                for (int slot = 0; slot < store.getContainerSize(); slot++) {
                    if (store.getItem(slot).isEmpty()) {
                        empty++;
                    } else {
                        stock.add(new Stock.Holding(at, store.getItem(slot).copy()));
                    }
                }
                room.put(at, empty);
            });
            Ways ways = new Ways() {
                public Faring journey(Resident who, WorldPos from, Set<WorldPos> goals) {
                    requests++;
                    goals.forEach(goal -> requested.merge(List.of(from, goal), 1, Integer::sum));
                    return Faring.yes(Journey.afoot(1, goals));
                }

                public Faring anyoneReaches(Set<WorldPos> goals) {
                    return Faring.yes(Journey.afoot(1, goals));
                }

                public TransportTravel estimates() {
                    if (forbidEstimates) {
                        throw new AssertionError("empty publication entered transport estimation");
                    }
                    return (kind, from, to, departure) -> {
                        estimates++;
                        if (from.equals(blocked) || to.equals(blocked)) {
                            return TransportTravel.NO;
                        }
                        if (unknownAll || unknownApproach && from.equals(pos(4)) && to.equals(pos(0))) {
                            return TransportTravel.LATER;
                        }
                        if (cutDirect && from.cell().getX() < pos(2).cell().getX() + 2
                                && to.cell().getX() > pos(2).cell().getX() + 2) {
                            return TransportTravel.NO;
                        }
                        return new TransportTravel.Yes(20L * from.cell().distManhattan(to.cell()), 0);
                    };
                }
            };
            return new Situation(ways, new Stock(stock, room), workshops, building.done(),
                () -> publication().in(helper.getLevel().dimension()), () -> revision, 0);
        }

        private void nested() {
            PlanningFixture middle = new PlanningFixture(helper);
            Crew.Hand hand = middle.crew.hands().getFirst();
            List<Crew.Hand> hands = new ArrayList<>(crew.hands());
            hands.add(new Crew.Hand(hand.who(), pos(2), 6, 6, 1,
                hand.licences(), List.of(), Optional.empty()));
            crew = new Crew(hands);
            workers.add(middle.worker);
            mode.line("outer", 1, 3, 1).line("inner", 1, 2, 1);
        }

        private void carried() {
            ItemStack goods = store(0).removeItem(0, 4);
            fixture.worker.pack().setItem(0, goods);
            for (int slot = 1; slot < fixture.worker.pack().getContainerSize(); slot++) {
                fixture.worker.pack().setItem(slot, new ItemStack(Items.DIAMOND, 64));
            }
            Crew.Hand hand = crew.hands().getFirst();
            crew = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 0, 6, 1,
                hand.licences(), hand.tools(), Optional.empty(), List.of(goods)), crew.hands().getLast()));
        }

        private void plan() {
            weaver.know(situation(), crew);
            owed.goals().forEach(work -> weaver.submit(MODE, work));
            view.apply(weaver.grow(false));
            helper.assertTrue(!view.vertices().isEmpty(),
                "the delivery has an executable plan: " + weaver.shortfalls());
        }

        private void run() {
            for (int i = 0; i < 60 && step(); i++) {
                helper.assertTrue(i < 59, "execution must terminate");
            }
        }

        private boolean step() {
            for (Vertex vertex : List.copyOf(view.vertices().values())) {
                Node node = vertex.node();
                if (view.before(node.id()).stream().anyMatch(view::has) || !node.ready(helper.getLevel())) {
                    continue;
                }
                UUID pinned = view.pin(node.id());
                Worker actor = pinned == null ? workers.stream().min(java.util.Comparator.comparingDouble(one ->
                    crew.hands().stream().filter(hand -> hand.id().equals(one.resident().id())).findFirst()
                        .orElseThrow().at().cell().distSqr(node.spec().site().where().cell()))).orElseThrow()
                    : workers.stream().filter(one -> one.resident().id().equals(pinned)).findFirst().orElseThrow();
                actor.body().moveTo(node.spec().site().where().cell().getBottomCenter());
                weaver.heldBy(node.id(), actor.resident().id(), true);
                Worker worker = worker(actor, vertex.plan());
                Outcome result = Commit.run(helper.getLevel(), node, worker);
                if (result instanceof Outcome.Done done) {
                    done.made().forEach(stack -> helper.assertTrue(Containers.insert(worker.pack(), stack).isEmpty(),
                        "a declared output fits its planned pack"));
                    node.ended(helper.getLevel(), Ending.DONE);
                    weaver.finished(node.id());
                } else {
                    weaver.failed(node.id(), ((Outcome.Failed) result).why());
                    view.apply(weaver.grow(false));
                    return false;
                }
                view.apply(weaver.grow(false));
                return true;
            }
            return false;
        }

        private Worker worker(Worker actor, Placement placement) {
            return new Worker() {
                public Resident resident() { return actor.resident(); }
                public Mob body() { return actor.body(); }
                public Container pack() { return actor.pack(); }

                public List<Store> within() {
                    return placement.from().stream().map(stash -> new Store(stash.pos(), stores.get(stash.pos())))
                        .toList();
                }

                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) {
                    if (!stack.isEmpty()) {
                        throw new AssertionError("unexpected spill: " + stack);
                    }
                }
                public Effects effects() { return Effects.NONE; }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return placement; }
            };
        }

        private long total() {
            return stores.values().stream().mapToLong(store -> Goods.countIn(store, GOODS)).sum()
                + workers.stream().mapToLong(one -> Goods.countIn(one.pack(), GOODS)).sum()
                + mode.mail.stream().mapToLong(box -> Goods.countIn(box, GOODS)).sum();
        }

        public void close() {
            weaver.closed();
            placed.forEach(pos ->
                helper.getLevel().setBlockAndUpdate(pos.cell(), Blocks.AIR.defaultBlockState()));
        }
    }

    private static final class Fake implements TransportMode {
        private final Rig rig;
        private final List<TransportLink> lines = new ArrayList<>();
        private final Map<TransportLink, Long> costs = new LinkedHashMap<>();
        private final List<Container> mail = new ArrayList<>();
        private final List<Long> departures = new ArrayList<>();
        private boolean arrived = true;
        private boolean failSend;
        private boolean rejectFirst;
        private boolean invalidateFirst;
        private boolean rejectNested;
        private boolean wideNested;
        private boolean refuseNested;
        private int childCalls;
        private int innerLaid;
        private final Set<UUID> abandoned = new java.util.HashSet<>();
        private long limit = Long.MAX_VALUE;
        private int discovered;
        private int quoted;
        private int laid;
        private int sent;
        private int taken;
        private int released;

        private Fake(Rig rig) {
            this.rig = rig;
        }

        private Fake line(String name, int from, int to, long cost) {
            var id = ResourceLocation.fromNamespaceAndPath("folkways", name);
            var link = new TransportLink(id, 1, endpoint(from), endpoint(to), List.of(GOODS),
                new TransportLink.Capacity(MODE, 64, 64, 1, false),
                new TransportLink.Service(MODE, 1, 0, Long.MAX_VALUE), true, true, true);
            lines.add(link);
            costs.put(link, cost);
            return this;
        }

        private TransportEndpoint endpoint(int index) {
            return new TransportEndpoint(ResourceLocation.fromNamespaceAndPath("folkways", "hub_" + index),
                rig.pos(index), Set.of(new Stand(rig.pos(index))),
                new TransportEndpoint.Handover(TransportEndpoint.Form.PACK, MODE));
        }

        public ResourceLocation id() { return MODE; }

        public List<TransportLink> links(TransportQuery query, TransportSnapshot snapshot) {
            discovered++;
            return lines.stream().filter(line -> !line.id().getPath().equals("inner")
                || query.to().at().equals(rig.pos(2))).toList();
        }

        public QuoteResult quote(TransportLink link, BatchRequest batch, TransportSnapshot snapshot) {
            quoted++;
            departures.add(batch.readyAt());
            if (rig.unknownAll) {
                return QuoteResult.LATER;
            }
            if (link.id().getPath().equals("blocked")) {
                return QuoteResult.NO;
            }
            long count = Math.min(limit, batch.count());
            long cost = costs.get(link);
            return new QuoteResult.Available(new TransportQuote(MODE, link, batch, count, cost,
                batch.readyAt() + cost, List.of(new TransportQuote.Load(count, 1,
                    batch.readyAt(), batch.readyAt() + cost)), List.of(), Map.of(MODE, snapshot.version()),
                Optional.empty()), true);
        }

        public LayResult lay(TransportQuote quote, TransportLaying plan) {
            laid++;
            Container box = new SimpleContainer(1);
            mail.add(box);
            long count = quote.covered();
            boolean outer = quote.link().id().getPath().equals("outer");
            boolean inner = quote.link().id().getPath().equals("inner");
            boolean composed = outer;
            if (inner) {
                innerLaid++;
            }
            WorldPos sendAt = composed ? rig.pos(2) : quote.link().from().at();
            NodeSpec sending = NodeSpec.of(UUID.randomUUID(), MODE, new WorkSite.AtBlock(sendAt),
                Stances.at(sendAt), Workload.Once.of(1))
                .needs(new Need(GOODS, count, true)).gives(new Amount(GOODS, count)).done();
            NodeSpec receiving = NodeSpec.of(UUID.randomUUID(), MODE, new WorkSite.AtBlock(quote.link().to().at()),
                Stances.at(quote.link().to().at()), Workload.Once.of(1)).gives(new Amount(GOODS, count)).done();
            Node send = new Node() {
                public NodeSpec spec() { return sending; }
                public Outcome commit(ServerLevel level, Worker who) {
                    if (failSend) {
                        return Outcome.failed(HaulRefusal.NOTHING_TO_TAKE);
                    }
                    who.supplied().forEach(stack -> rig.helper.assertTrue(Containers.insert(box, stack).isEmpty(),
                        "the transport receives the actual supplied goods"));
                    sent++;
                    return Outcome.done();
                }
            };
            Node receive = new Node() {
                public NodeSpec spec() { return receiving; }
                public boolean ready(ServerLevel level) { return arrived && Goods.countIn(box, GOODS) == count; }
                public Outcome commit(ServerLevel level, Worker who) {
                    if (!ready(level)) {
                        return Outcome.failed(HaulRefusal.NOTHING_TO_TAKE);
                    }
                    return new Outcome.Done(List.of(), List.of(box.removeItem(0, (int) count)), Optional.empty());
                }
            };
            plan.add(send);
            plan.add(receive);
            plan.flow(send.id(), receive.id(), GOODS, count);
            Node bridge = null;
            if (composed) {
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), MODE,
                    new WorkSite.AtBlock(quote.link().from().at()), Stances.at(quote.link().from().at()),
                    Workload.Once.of(1)).needs(new Need(GOODS, count, true)).gives(new Amount(GOODS, count)).done();
                bridge = new Node() {
                    public NodeSpec spec() { return spec; }
                    public Outcome commit(ServerLevel level, Worker who) {
                        return new Outcome.Done(List.of(), List.of(), Optional.empty(), who.supplied());
                    }
                };
                plan.add(bridge);
                abandoned.add(bridge.id());
                abandoned.add(send.id());
                abandoned.add(receive.id());
            }
            if (!plan.reserve(quote.link().from().id(), MODE, send.id(), 1, 16) || !plan.hold(new Hold() {
                public Optional<io.github.izakyl.folkways.core.api.terms.RefusalKind> take() {
                    taken++;
                    return Optional.empty();
                }
                public void release(Ending how) { released++; }
            }) || !plan.feed(bridge == null ? send.id() : bridge.id())
                    || !plan.output(receive.id())) {
                return LayResult.UNAVAILABLE;
            }
            if (bridge != null) {
                boolean transferred = false;
                for (int attempt = 0; attempt < (wideNested ? 10_000 : 1); attempt++) {
                    childCalls++;
                    // A transfer of more than the leg carries is refused, as a carry that cannot be made.
                    if (plan.transfer(bridge.id(), send.id(), GOODS, refuseNested ? count + 1 : count)) {
                        transferred = true;
                        break;
                    }
                }
                if (!transferred || outer && rejectNested) {
                    return LayResult.UNAVAILABLE;
                }
            }
            if (invalidateFirst && quote.link().id().getPath().equals("rejected")) {
                rig.revision++;
            }
            return rejectFirst && quote.link().id().getPath().equals("rejected")
                ? LayResult.UNAVAILABLE : LayResult.LAID;
        }
    }
}
