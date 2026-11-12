package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.ClaimRelease;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks;
import io.github.izakyl.folkways.core.front.api.Facing;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.TextColor;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.StoreReadings;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort.Operation;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Stock;
import io.github.izakyl.folkways.core.plan.internal.Claims;
import io.github.izakyl.folkways.core.plan.internal.Cooldowns;
import io.github.izakyl.folkways.core.plan.internal.Crew;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.plan.internal.Weaver;
import io.github.izakyl.folkways.core.plan.internal.WeaveView;
import io.github.izakyl.folkways.core.testing.TestWorker;
import io.github.izakyl.folkways.core.travel.internal.Ways;
import io.github.izakyl.folkways.core.travel.internal.Faring;
import io.github.izakyl.folkways.core.travel.internal.Journey;
import io.github.izakyl.folkways.core.transport.api.TransportTravel;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.plan.internal.Situation;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Manifest;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import io.github.izakyl.folkways.core.work.internal.Commit;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorePortGameTests {

    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "ports");
    private static final Map<WorldPos, FakePort> PORTS = new LinkedHashMap<>();

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        StorePorts.register(OWNER, StorePorts.CONTAINERS,
            (level, pos) -> Optional.ofNullable(PORTS.get(WorldPos.of(level, pos))));
        event.register(StorePortGameTests.class);
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void residentTakesAndPutsThroughSlotsAndPoolPorts(GameTestHelper helper) {
        var planning = new PlanningFixture(helper);
        var level = helper.getLevel();
        try (Fixture f = new Fixture(helper)) {
            for (boolean pooled : List.of(false, true)) {
                Bulk source = new Bulk(pooled);
                source.goods.put(Items.BREAD.getDefaultInstance().getItem(), 1_024L);
                Bulk target = new Bulk(!pooled);
                f.port(planning.source, source, Set.of(Operation.TAKE, Operation.PUT));
                f.port(planning.target, target, Set.of(Operation.TAKE, Operation.PUT));
                f.offer();
                Survey survey = f.survey();
                helper.assertTrue(stock(survey).available(ItemSpec.of(Items.BREAD)) == 1_024,
                    "bulk quantities are read from entries");
                helper.assertTrue(f.colony.store(level, planning.source).isEmpty(), "a bulk port is not a Container");
                var order = new PlanningFixture.Owed(new WorkSite.AtBlock(planning.target),
                    Stances.at(planning.target), ItemSpec.of(Items.BREAD), 96);
                var rounds = new PlanningFixture.Rounds();
                try {
                    var solved = rounds.solve(new Situation(planning.ways, stock(survey), List.of(), planning.stands),
                        planning.crew, order.goals(), List.of(), 0);
                    var tour = solved.schedule().tours().get(planning.worker.resident().id());
                    helper.assertTrue(tour != null && tour.nodes().size() == 2,
                        "the planner schedules a bulk take and a delivery");
                    for (UUID id : tour.nodes()) {
                        var node = solved.weave().vertices().get(id).node();
                        helper.assertTrue(Commit.run(level, node, planning.worker) instanceof Outcome.Done,
                            "the resident executes the planned transfer through the port");
                        node.ended(level, Ending.DONE);
                    }
                    helper.assertTrue(target.count() == 96 && source.count() == 928
                        && planning.worker.pack().isEmpty() && order.complete(),
                        "all requested goods arrive unchanged");
                } finally {
                    rounds.closed();
                }
                helper.assertTrue(source.simulations > 0 && target.simulations > 0,
                    "both operations simulate before executing");
            }
        } finally {
            planning.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void withheldPortsKeepStorageWithoutPromisingLots(GameTestHelper helper) {
        var planning = new PlanningFixture(helper);
        try (Fixture f = new Fixture(helper)) {
            ItemSpec bread = ItemSpec.of(Items.BREAD);
            for (boolean pooled : List.of(false, true)) {
                Bulk bulk = new Bulk(pooled);
                bulk.goods.put(Items.BREAD, 64L);
                bulk.newKinds = 0;
                f.port(planning.target, bulk, Set.of(Operation.TAKE, Operation.PUT));
                f.offer();
                f.colony.withhold(OWNER, f.level.dimension(), List.of(planning.target));
                Survey survey = f.survey();
                Stock stock = stock(survey);
                Stash where = Stash.at(planning.target);
                var reading = f.data.inventory().snapshot().store(planning.target).orElseThrow();
                helper.assertTrue(!reading.labor() && reading.storage(),
                    "withhold excludes ordinary supply but keeps the registered storage target");
                helper.assertTrue(stock.available(bread) == 0 && stock.lotsOf(bread).isEmpty()
                    && stock.heldAt(where, bread) == 0 && !stock.takes(where),
                    "the Workshop alone can promise the physical goods");
                long room = stock.roomAt(where, bread);
                helper.assertTrue(room > 32 && stock.stores(where)
                    && stock.roomAfter(where, bread, false, Map.of(bread, 32L), Map.of()) == room - 32,
                    "storage retains its room and deducts Space reservations");
                helper.assertTrue(!reading.approach().putting().isEmpty(), "PUT approaches remain observed");
                planning.worker.pack().setItem(0, new ItemStack(Items.BREAD, 32));
                var put = TransferNode.at(TransferNode.Endpoint.pack(), TransferNode.Endpoint.store(where),
                    new Manifest(List.of(new ItemStack(Items.BREAD, 32)), List.of()),
                    reading.approach().putting()).orElseThrow();
                helper.assertTrue(Commit.run(f.level, put, planning.worker) instanceof Outcome.Done
                    && bulk.count() == 96 && planning.worker.pack().isEmpty(),
                    "a resident can put goods into the withheld port");
                f.colony.withhold(OWNER, f.level.dimension(), List.of());
                helper.assertTrue(stock(f.survey()).available(bread) == 96,
                    "removing withhold restores ordinary supply");
            }
        } finally {
            planning.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void packWorkshopsRetainSupplyUnlessExplicitlyWithheld(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Bulk bulk = new Bulk(true);
            bulk.goods.put(Items.BREAD, 64L);
            WorldPos at = f.at(1, 1, 1);
            WorldPos alias = f.at(3, 1, 1);
            f.port(at, bulk, Set.of(Operation.TAKE, Operation.PUT));
            f.port(alias, bulk, Set.of(Operation.TAKE, Operation.PUT));
            f.offer();
            Workshop pack = new Workshop() {
                private final UUID key = UUID.randomUUID();

                @Override
                public UUID key() {
                    return key;
                }

                @Override
                public ResourceLocation id() {
                    return OWNER;
                }

                @Override
                public WorkSite site() {
                    return new WorkSite.AtBlock(alias);
                }

                @Override
                public boolean withholdsStore() {
                    return false;
                }
            };
            f.colony.publish(OWNER, f.level.dimension(), List.of(pack));
            ItemSpec bread = ItemSpec.of(Items.BREAD);
            Stock stock = stock(f.survey());
            helper.assertTrue(stock.available(bread) == 64 && stock.stores(Stash.at(at))
                && stock.roomAt(Stash.at(at), bread) > 0, "pack workshop keeps supply and storage across aliases");
            f.colony.withhold(OWNER, f.level.dimension(), List.of(alias));
            stock = stock(f.survey());
            helper.assertTrue(stock.available(bread) == 0 && stock.lotsOf(bread).isEmpty()
                && stock.stores(Stash.at(at)) && stock.roomAt(Stash.at(at), bread) > 0,
                "explicit withhold overrides the pack exception but retains storage across aliases");
            f.colony.withhold(OWNER, f.level.dimension(), List.of());
            helper.assertTrue(stock(f.survey()).available(bread) == 64, "removing withhold restores pack store supply");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void aFullPackDoesNotWithdrawFromATakeOnlyPort(GameTestHelper helper) {
        var planning = new PlanningFixture(helper);
        try (Fixture f = new Fixture(helper)) {
            Bulk bulk = new Bulk(true);
            bulk.slots = true;
            bulk.goods.put(Items.BREAD, 100L);
            f.port(planning.source, bulk, Set.of(Operation.TAKE));
            f.offer();
            Survey survey = f.survey();
            helper.assertFalse(stock(survey).direct(Stash.at(planning.source)),
                "a capability's slot reading does not promise legacy Container access");
            for (int slot = 0; slot < planning.worker.pack().getContainerSize(); slot++) {
                planning.worker.pack().setItem(slot, new ItemStack(Items.BREAD, 64));
            }
            var take = TransferNode.at(TransferNode.Endpoint.store(Stash.at(planning.source)),
                TransferNode.Endpoint.pack(), new Manifest(List.of(new ItemStack(Items.BREAD)), List.of()),
                Set.of(Stand.at(planning.source))).orElseThrow();
            helper.assertTrue(Commit.run(f.level, take, planning.worker) instanceof Outcome.Failed,
                "a full pack refuses the collection before touching a take-only source");
            helper.assertTrue(bulk.count() == 100 && bulk.simulations == 1,
                "only simulation ran, so no return port or spill is needed");
        } finally {
            planning.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void aliasesCountOnceAndKeepTheirOperationStands(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Bulk bulk = new Bulk(true);
            bulk.goods.put(Items.BREAD, 5_000_000_000L);
            WorldPos take = f.at(1, 1, 1);
            WorldPos put = f.at(12, 1, 1);
            f.port(put, bulk, Set.of(Operation.PUT));
            f.port(take, bulk, Set.of(Operation.TAKE));
            f.offer();
            Survey survey = f.survey();
            var inventory = f.colony.inventory().snapshot();
            helper.assertTrue(inventory.stores().size() == 1, "one physical identity has one inventory");
            var store = inventory.store(put).orElseThrow();
            helper.assertTrue(store.stash().equals(Stash.at(take)) && store.aliases().containsAll(Set.of(take, put)),
                "registered minimum coordinate anchors both aliases");
            helper.assertTrue(store.operations().get(take).equals(Set.of(Operation.TAKE))
                && store.operations().get(put).equals(Set.of(Operation.PUT)), "each alias retains its operations");
            helper.assertTrue(stock(survey).available(ItemSpec.of(Items.BREAD)) == 5_000_000_000L
                && ColonyStocks.available(f.colony, f.level.getServer(), ItemSpec.of(Items.BREAD)) == 5_000_000_000L,
                "planning and coverage count long quantities once");
            helper.assertTrue(!store.approach().taking().isEmpty() && !store.approach().putting().isEmpty()
                && java.util.Collections.disjoint(store.approach().taking(), store.approach().putting()),
                "distant access devices expose disjoint operation stands");
            helper.assertTrue(survey.stands().around(store.stash(), Operation.TAKE).equals(store.approach().taking())
                && survey.stands().around(store.stash(), Operation.PUT).equals(store.approach().putting()),
                "the planner receives operation-specific stands");
            helper.assertTrue(store.slots().getFirst().stackCopy().getCount() == 64
                && store.slots().getLast().stackCopy().getCount() == 64,
                "the compatibility view splits bulk entries into ordinary stacks lazily");
            helper.assertTrue(Containers.anchor(f.level, put.block(f.level)).equals(take.block(f.level)),
                "anchor lookup follows the physical identity");
            f.colony.release(f.colony.claimAt(take).orElseThrow().id(), ClaimRelease.LET_GO);
            f.registered.remove(take);
            f.offer();
            f.survey();
            var remaining = f.colony.inventory().snapshot().store(put).orElseThrow();
            helper.assertTrue(remaining.stash().equals(Stash.at(put)) && remaining.aliases().equals(Set.of(put))
                && f.colony.inventory().snapshot().store(take).isEmpty(),
                "only registered access points remain aliases");
            Bulk separate = new Bulk(false);
            separate.goods.put(Items.BREAD, 7L);
            f.port(take, separate, Set.of(Operation.TAKE, Operation.PUT));
            f.offer();
            f.survey();
            helper.assertTrue(f.colony.inventory().snapshot().stores().size() == 2,
                "an access point that joins another physical store becomes a separate inventory");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void unusableStoresDoNotFeedLaborOrCoverageAndCanRecover(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Bulk bulk = new Bulk(false);
            bulk.goods.put(Items.BREAD, 120L);
            bulk.usability = Usability.because(Component.literal("unsafe setup"));
            WorldPos at = f.at(1, 1, 1);
            f.port(at, bulk, Set.of(Operation.TAKE, Operation.PUT));
            f.offer();
            Survey survey = f.survey();
            helper.assertTrue(stock(survey).available(ItemSpec.of(Items.BREAD)) == 0
                && stock(survey).stashes().isEmpty(), "an unusable store offers neither labor goods nor room");
            helper.assertTrue(ColonyStocks.available(f.colony, f.level.getServer(), ItemSpec.of(Items.BREAD)) == 0,
                "its goods do not cover stock targets");
            var store = f.colony.inventory().snapshot().store(at).orElseThrow();
            helper.assertTrue(!store.usability().usable() && store.entries().getFirst().count() == 120,
                "the panel still has the store's goods and reason");
            var board = Facing.of(f.colony, WaresContent.ID)
                .flatMap(facing -> facing.board(WaresContent.ID, f.colony.view(f.level))).orElseThrow();
            helper.assertTrue(board.rows().stream().anyMatch(row -> row.detail().getString().contains("unsafe setup")
                && TextColor.fromLegacyFormat(ChatFormatting.GRAY).equals(row.title().getStyle().getColor())),
                "the colony panel lists the unusable store in grey with its reason");
            bulk.usability = Usability.USABLE;
            survey = f.survey();
            helper.assertTrue(stock(survey).available(ItemSpec.of(Items.BREAD)) == 120,
                "a setup verdict is re-evaluated without re-registration");
            helper.assertTrue(ColonyStocks.available(f.colony, f.level.getServer(), ItemSpec.of(Items.BREAD)) == 120,
                "coverage cache follows usability policy changes");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void revisionsRefreshGoodsWithoutChangeEvents(GameTestHelper helper) {
        try (Fixture f = new Fixture(helper)) {
            Bulk bulk = new Bulk(true);
            bulk.goods.put(Items.BREAD, 10L);
            WorldPos at = f.at(1, 1, 1);
            f.port(at, bulk, Set.of(Operation.TAKE, Operation.PUT));
            f.offer();
            f.survey();
            bulk.goods.put(Items.BREAD, 24L);
            bulk.revision++;
            helper.assertTrue(stock(f.survey()).available(ItemSpec.of(Items.BREAD)) == 24,
                "revision alone refreshes an observed store");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void storageTrunkWinsBetweenDistantAliases(GameTestHelper helper) {
        try (TrunkRig rig = new TrunkRig(helper)) {
            rig.plan();
            helper.assertTrue(rig.transit() == 4 && rig.view.vertices().size() == 4,
                "the directed delivery lays source take, network put, network take and destination put");
            rig.run();
            helper.assertTrue(rig.order.complete() && rig.network.count() == 0 && rig.target.count() == 4
                    && rig.source.count() == 0 && rig.claims.size() == 0,
                "two carriers deliver through the same pooled store without duplicating stock or claims");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void storageTrunkLosesToAShortDirectCarry(GameTestHelper helper) {
        try (TrunkRig rig = new TrunkRig(helper)) {
            rig.destination = rig.f.at(6, 1, 3);
            rig.installTarget();
            rig.plan();
            helper.assertTrue(rig.transit() == 0 && rig.view.vertices().size() == 2,
                "the direct carry wins when detouring through both aliases costs more walking");
            rig.run();
            helper.assertTrue(rig.order.complete() && rig.target.count() == 4 && rig.network.count() == 0,
                "the direct route delivers the actual goods");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void storageTransitBooksSharedRoomUntilTaken(GameTestHelper helper) {
        try (TrunkRig rig = new TrunkRig(helper)) {
            rig.plan();
            Stash network = rig.networkStash();
            helper.assertTrue(rig.transit() == 4
                    && rig.claims.taken(new Resource.Space(network, rig.goods)) == 4,
                "the future take owns all transit room before the source moves");
            Stock stock = rig.world().stock();
            helper.assertTrue(stock.roomAfter(network, ItemSpec.of(Items.IRON_INGOT), false,
                    Map.of(rig.goods, 4L), Map.of()) == 0,
                "a different kind cannot book the same network room");
            rig.step();
            rig.step();
            helper.assertTrue(rig.network.count() == 4 && rig.transit() == 4
                    && rig.claims.taken(new Resource.Space(network, rig.goods)) == 4,
                "finishing the put keeps both the room and the deposited stock reserved");
            rig.step();
            helper.assertTrue(rig.network.count() == 0 && rig.transit() == 0
                    && rig.claims.taken(new Resource.Space(network, rig.goods)) == 0,
                "only the completed take releases transit occupancy");
            rig.run();
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void goodsConsumedInStorageTransitCauseACleanReplan(GameTestHelper helper) {
        try (TrunkRig rig = new TrunkRig(helper)) {
            rig.plan();
            rig.step();
            rig.step();
            helper.assertTrue(rig.network.count() == 4, "the real batch has been put into the network");
            rig.network.goods.clear();
            rig.network.revision++;
            Outcome outcome = rig.step();
            helper.assertTrue(outcome instanceof Outcome.Failed && rig.transit() == 0
                    && rig.target.count() == 0 && rig.packs() == 0
                    && rig.claims.taken(new Resource.Space(rig.networkStash(), rig.goods)) == 0,
                "a missing take refuses, clears the route and creates no replacement goods");
            helper.assertTrue(rig.view.vertices().values().stream().noneMatch(one ->
                    one.node() instanceof TransferNode transfer && transfer.fetching())
                    && !rig.weaver.shortfalls().isEmpty(),
                "current empty stock leaves a shortfall instead of replaying the lost batch; nodes="
                    + rig.view.vertices() + "; shortfalls=" + rig.weaver.shortfalls());
            rig.source.goods.put(Items.BREAD, 4L);
            rig.source.revision++;
            rig.weaver.know(rig.world(), rig.crew());
            rig.order.goals().forEach(work -> rig.weaver.submit(OWNER, work));
            rig.view.apply(rig.weaver.grow(true));
            rig.run();
            helper.assertTrue(rig.order.complete() && rig.target.count() == 4 && rig.source.count() == 0
                    && rig.network.count() == 0 && rig.packs() == 0,
                "newly observed replacement stock completes the outstanding delivery exactly once");
        }
        helper.succeed();
    }

    @GameTest(template = "empty_large", templateNamespace = "folkways_core")
    public static void storageTrunkHonorsRulesWithholdAndUsability(GameTestHelper helper) {
        try (TrunkRig rig = new TrunkRig(helper)) {
            for (int denied = 0; denied < 5; denied++) {
                rig.network.usability = denied == 0
                    ? Usability.because(Component.literal("booting")) : Usability.USABLE;
                rig.f.colony.withhold(OWNER, rig.f.level.dimension(), denied == 1 ? List.of(rig.put) : List.of());
                rig.f.colony.rule(OWNER, rig.f.level.dimension(), denied == 2
                    ? List.of(new StoreRule.Access(rig.put, false, true)) : denied == 3
                        ? List.of(new StoreRule.Access(rig.put, true, false)) : denied == 4
                            ? List.of(new StoreRule.Admit(rig.put, true, List.of(ItemSpec.of(Items.IRON_INGOT))))
                            : List.of());
                var world = rig.world();
                var query = new io.github.izakyl.folkways.core.transport.api.TransportQuery(
                    new io.github.izakyl.folkways.core.transport.api.TransportEndpoint(OWNER, rig.start,
                        Set.of(Stand.at(rig.start)), new io.github.izakyl.folkways.core.transport.api.TransportEndpoint
                            .Handover(io.github.izakyl.folkways.core.transport.api.TransportEndpoint.Form.PACK, OWNER)),
                    new io.github.izakyl.folkways.core.transport.api.TransportEndpoint(OWNER, rig.destination,
                        Set.of(Stand.at(rig.destination)),
                        new io.github.izakyl.folkways.core.transport.api.TransportEndpoint
                            .Handover(io.github.izakyl.folkways.core.transport.api.TransportEndpoint.Form.PACK, OWNER)),
                    new io.github.izakyl.folkways.core.transport.api.BatchRequest(rig.goods, 4, 0), List.of());
                helper.assertTrue(world.transports().get().entries().stream().noneMatch(entry ->
                    !entry.offering().mode().links(query, entry.offering().snapshot()).isEmpty()),
                    "unusable, withheld and permission-restricted networks offer no storage trunk, case=" + denied);
            }
        }
        helper.succeed();
    }

    private static final class TrunkRig implements AutoCloseable {
        final GameTestHelper helper;
        final Fixture f;
        final PlanningFixture first;
        final PlanningFixture second;
        final Bulk source = new Bulk(false);
        final Bulk network = new Bulk(true);
        final Bulk target = new Bulk(false);
        final Claims claims = new Claims();
        final Weaver weaver = new Weaver(claims, new Cooldowns());
        final WeaveView view = new WeaveView();
        final ItemSpec goods = Goods.specOf(Items.BREAD.getDefaultInstance());
        final WorldPos start;
        final WorldPos put;
        final WorldPos take;
        WorldPos destination;
        PlanningFixture.Owed order;

        TrunkRig(GameTestHelper helper) {
            this.helper = helper;
            f = new Fixture(helper);
            first = new PlanningFixture(helper);
            second = new PlanningFixture(helper);
            start = f.at(1, 1, 3);
            put = f.at(4, 1, 3);
            take = f.at(12, 1, 3);
            destination = f.at(15, 1, 3);
            source.goods.put(Items.BREAD, 4L);
            network.capacity = 4;
            integrated(start, source, Set.of(Operation.TAKE, Operation.PUT));
            integrated(put, network, Set.of(Operation.PUT));
            integrated(take, network, Set.of(Operation.TAKE));
            installTarget();
        }

        void integrated(WorldPos at, Bulk bulk, Set<Operation> operations) {
            f.port(at, bulk, operations);
            helper.assertTrue(StorePorts.at(f.level, at.block(f.level)).orElseThrow().usability().usable(),
                "the shared fixture has an explicit storage integration");
        }

        void installTarget() {
            integrated(destination, target, Set.of(Operation.TAKE, Operation.PUT));
            f.offer();
            order = new PlanningFixture.Owed(new WorkSite.AtBlock(destination),
                Stances.at(destination.at(destination.cell().south())), goods, 4);
        }

        Crew crew() {
            Crew.Hand one = first.crew.hands().getFirst();
            Crew.Hand two = second.crew.hands().getFirst();
            return new Crew(List.of(new Crew.Hand(one.who(), start, 6, 6, 1,
                one.licences(), List.of(), Optional.empty()),
                new Crew.Hand(two.who(), destination, 6, 6, 1, two.licences(), List.of(), Optional.empty())));
        }

        Situation world() {
            Ways ways = new Ways() {
                public Faring journey(io.github.izakyl.folkways.core.resident.api.Resident who,
                        WorldPos from, Set<WorldPos> goals) {
                    return Faring.yes(Journey.afoot(1, goals));
                }

                public Faring anyoneReaches(Set<WorldPos> goals) {
                    return Faring.yes(Journey.afoot(1, goals));
                }

                public TransportTravel estimates() {
                    return (kind, from, to, departure) ->
                        new TransportTravel.Yes(20L * from.cell().distManhattan(to.cell()), 0);
                }
            };
            return f.survey().known(ways, List.of()).world();
        }

        Stash networkStash() {
            return f.colony.inventory().snapshot().store(put).orElseThrow().stash();
        }

        long transit() {
            return claims.totals().entrySet().stream().filter(entry -> entry.getKey() instanceof Resource.Transit)
                .mapToLong(Map.Entry::getValue).sum();
        }

        long packs() {
            return Goods.countIn(first.worker.pack(), goods) + Goods.countIn(second.worker.pack(), goods);
        }

        void plan() {
            weaver.know(world(), crew());
            order.goals().forEach(work -> weaver.submit(OWNER, work));
            view.apply(weaver.grow(false));
            helper.assertTrue(weaver.shortfalls().isEmpty(), "the delivery is fully planned: " + weaver.shortfalls());
        }

        Outcome step() {
            var vertex = view.vertices().values().stream()
                .filter(one -> view.before(one.id()).stream().noneMatch(view::has)).findFirst().orElseThrow();
            Node node = vertex.node();
            UUID pinned = view.pin(node.id());
            Worker actor = pinned == null
                ? vertex.plan().stands().stream()
                    .anyMatch(stand -> stand.cell().cell().getX() >= take.cell().getX() - 3)
                    ? second.worker : first.worker
                : pinned.equals(first.worker.resident().id()) ? first.worker : second.worker;
            WorldPos footing = vertex.plan().stands().stream().findFirst().orElseThrow().cell();
            actor.body().moveTo(footing.cell().getBottomCenter());
            Worker worker = new TestWorker(actor::resident, actor::body, actor::pack, vertex.plan(), List.of());
            weaver.heldBy(node.id(), actor.resident().id(), true);
            Outcome outcome = Commit.run(f.level, node, worker);
            if (outcome instanceof Outcome.Done) {
                node.ended(f.level, Ending.DONE);
                weaver.finished(node.id());
            } else {
                weaver.failed(node.id(), ((Outcome.Failed) outcome).why());
                weaver.know(world(), crew());
            }
            view.apply(weaver.grow(false));
            return outcome;
        }

        void run() {
            for (int step = 0; step < 16 && !view.vertices().isEmpty(); step++) {
                helper.assertTrue(step() instanceof Outcome.Done, "each planned transfer succeeds");
            }
        }

        public void close() {
            weaver.closed();
            first.worker.body().discard();
            second.worker.body().discard();
            f.close();
        }
    }

    private static Stock stock(Survey survey) {
        return survey.known(PlanningFixture.afoot(1), List.of()).world().stock();
    }

    private static final class Fixture implements AutoCloseable {

        final ServerLevel level;
        final GameTestHelper helper;
        final Colony colony;
        final ColonyData data;
        final Set<WorldPos> registered = new java.util.LinkedHashSet<>();

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            level = helper.getLevel();
            colony = Colonies.mint(level.getServer());
            data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 6; z++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 0, z)),
                        Blocks.STONE.defaultBlockState());
                }
            }
        }

        WorldPos at(int x, int y, int z) {
            return WorldPos.of(level, helper.absolutePos(new BlockPos(x, y, z)));
        }

        void port(WorldPos at, Bulk bulk, Set<Operation> operations) {
            level.setBlockAndUpdate(at.block(level), Blocks.BEACON.defaultBlockState());
            PORTS.put(at, new FakePort(at, bulk, operations));
            StorePorts.changed(level, at.block(level));
            if (registered.add(at)) {
                colony.hold(OWNER, new Claimed.Block(at)).orElseThrow();
            }
        }

        void offer() {
            colony.stores(OWNER, level.dimension(), List.copyOf(registered));
        }

        Survey survey() {
            InventoryObservations.publish(colony, level);
            Survey survey = new Survey();
            survey.answer(level, data, Map.of(), body -> { });
            InventoryObservations.work(data.inventory(), level.getServer(), 1_000);
            survey.answer(level, data, Map.of(), body -> { });
            return survey;
        }

        @Override
        public void close() {
            registered.forEach(PORTS::remove);
            colony.raze();
        }
    }

    private static final class Bulk {

        final UUID identity = UUID.randomUUID();
        final boolean pooled;
        final Map<net.minecraft.world.item.Item, Long> goods = new LinkedHashMap<>();
        Usability usability = Usability.USABLE;
        boolean slots;
        long newKinds = 8;
        long capacity = Long.MAX_VALUE;
        long revision;
        int simulations;

        Bulk(boolean pooled) {
            this.pooled = pooled;
        }

        long count() {
            return goods.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    private record FakePort(WorldPos at, Bulk bulk, Set<Operation> operations) implements StorePort {

        @Override
        public PortIdentity identity() {
            return new PortIdentity(bulk.identity, at);
        }

        @Override
        public Usability usability() {
            return bulk.usability;
        }

        @Override
        public OptionalLong revision() {
            return OptionalLong.of(bulk.revision);
        }

        @Override
        public PortReader reader(ServerLevel level, List<StoreRule> rules) {
            if (!bulk.slots && rules.stream().anyMatch(StoreRule.Slots.class::isInstance)) {
                throw new AssertionError("slotless observations must not receive invented slot ranges");
            }
            List<PortReader.Entry> entries = bulk.goods.entrySet().stream().filter(entry -> entry.getValue() > 0)
                .map(entry -> new PortReader.Entry(entry.getKey().getDefaultInstance(), entry.getValue())).toList();
            Room room = bulk.pooled ? new Room.Pool(Math.max(0, bulk.capacity - bulk.count()), bulk.newKinds)
                : new Room.Slots(List.of(new Room.Cell(Items.BREAD.getDefaultInstance(),
                    Math.max(0, 8_192 - bulk.goods.getOrDefault(Items.BREAD, 0L)))), 0, new Room.Cap(0, 1));
            return new PortReader() {
                private int read;

                @Override
                public boolean step(int budget) {
                    read += budget;
                    return read >= Math.max(1, entries.size());
                }

                @Override
                public PortReading result() {
                    Optional<List<SlotSnapshot>> slots = bulk.slots
                        ? Optional.of(StoreReadings.synthetic(entries)) : Optional.empty();
                    return new PortReading(entries, slots, room, Set.of(), false);
                }
            };
        }

        @Override
        public TransferResult take(ServerLevel level, List<Pull> requested, List<StoreRule> rules, boolean simulate) {
            if (!bulk.slots && rules.stream().anyMatch(StoreRule.Slots.class::isInstance)) {
                throw new AssertionError("slotless transfers must not receive invented slot ranges");
            }
            if (!usability().usable() || !operations.contains(Operation.TAKE) || !StoreRule.takes(rules)) {
                return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
            }
            Map<net.minecraft.world.item.Item, Long> next = new LinkedHashMap<>(bulk.goods);
            List<ItemStack> moved = new ArrayList<>();
            for (Pull pull : requested) {
                long left = pull instanceof Pull.Exact exact ? exact.stack().getCount()
                    : ((Pull.Matching) pull).count();
                for (var entry : next.entrySet()) {
                    ItemStack prototype = entry.getKey().getDefaultInstance();
                    boolean matches = pull instanceof Pull.Exact exact
                        ? ItemStack.isSameItemSameComponents(prototype, exact.stack())
                        : Goods.matches(prototype, ((Pull.Matching) pull).spec());
                    if (!matches) {
                        continue;
                    }
                    long taken = Math.min(left, entry.getValue());
                    entry.setValue(entry.getValue() - taken);
                    left -= taken;
                    while (taken > 0) {
                        int count = (int) Math.min(taken, prototype.getMaxStackSize());
                        moved.add(prototype.copyWithCount(count));
                        taken -= count;
                    }
                }
                if (left != 0) {
                    return new TransferResult.Refused(TransferResult.Why.NOTHING_TO_TAKE);
                }
            }
            if (simulate) {
                bulk.simulations++;
            } else {
                bulk.goods.clear();
                bulk.goods.putAll(next);
                bulk.revision++;
            }
            return new TransferResult.Done(moved);
        }

        @Override
        public TransferResult put(ServerLevel level, List<ItemStack> goods, List<StoreRule> rules,
                                  boolean directed, boolean simulate) {
            if (!bulk.slots && rules.stream().anyMatch(StoreRule.Slots.class::isInstance)) {
                throw new AssertionError("slotless transfers must not receive invented slot ranges");
            }
            if (!usability().usable() || !operations.contains(Operation.PUT)
                || !directed && (!StoreRule.stores(rules)
                    || goods.stream().anyMatch(stack -> !StoreRule.admits(rules, stack)))) {
                return new TransferResult.Refused(TransferResult.Why.NOT_ALLOWED);
            }
            if (goods.stream().mapToLong(ItemStack::getCount).sum() > bulk.capacity - bulk.count()) {
                return new TransferResult.Refused(TransferResult.Why.NO_ROOM);
            }
            if (simulate) {
                bulk.simulations++;
            } else {
                goods.forEach(stack -> bulk.goods.merge(stack.getItem(), (long) stack.getCount(), Long::sum));
                bulk.revision++;
            }
            return new TransferResult.Done(goods);
        }
    }
}
