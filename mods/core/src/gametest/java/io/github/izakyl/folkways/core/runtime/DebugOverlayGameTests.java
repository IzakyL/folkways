package io.github.izakyl.folkways.core.runtime;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.colony.internal.ColonyData;
import io.github.izakyl.folkways.core.front.api.notice.DebugText;
import io.github.izakyl.folkways.core.front.api.notice.Line;
import io.github.izakyl.folkways.core.front.engine.net.DebugFocusPacket;
import io.github.izakyl.folkways.core.front.engine.net.DebugTarget;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.PortReader;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import io.github.izakyl.folkways.core.inventory.api.port.TransferResult;
import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.internal.Claim;
import io.github.izakyl.folkways.core.plan.internal.PlanningFixture;
import io.github.izakyl.folkways.core.plan.internal.Resource;
import io.github.izakyl.folkways.core.plan.internal.Vertex;
import io.github.izakyl.folkways.core.plan.internal.WeaveDelta;
import io.github.izakyl.folkways.core.resident.api.Balk;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.work.api.LaborRefusal;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Placement;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.netty.buffer.Unpooled;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DebugOverlayGameTests {
    private static final Map<WorldPos, BulkStore> BULK = new LinkedHashMap<>();

    private static final class BulkStore implements StorePort {
        private final WorldPos at;
        private long count = 3_000_000_000L;
        private boolean complete = true;

        private BulkStore(WorldPos at) {
            this.at = at;
        }

        @Override
        public PortIdentity identity() {
            return PortIdentity.block(at);
        }

        @Override
        public Usability usability() {
            return Usability.USABLE;
        }

        @Override
        public Set<Operation> operations() {
            return Set.of(Operation.TAKE, Operation.PUT);
        }

        @Override
        public PortReader reader(ServerLevel level, List<StoreRule> rules) {
            var reading = new PortReader.PortReading(
                List.of(new PortReader.Entry(new ItemStack(Items.IRON_INGOT), count)),
                Optional.empty(), Room.NONE, Set.of(), false);
            return new PortReader() {
                @Override
                public boolean step(int budget) {
                    return complete;
                }

                @Override
                public PortReading result() {
                    return reading;
                }
            };
        }

        @Override
        public TransferResult take(ServerLevel level, List<Pull> requested, List<StoreRule> rules, boolean simulate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TransferResult put(ServerLevel level, List<ItemStack> goods, List<StoreRule> rules,
                                  boolean directed, boolean simulate) {
            throw new UnsupportedOperationException();
        }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "debug_bulk_test"),
            StorePorts.SPECIFIC, (level, pos) -> Optional.ofNullable(BULK.get(WorldPos.of(level, pos))));
        event.register(DebugOverlayGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void residentReadCopiesCurrentAndQueuedSitesWithoutDraining(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            var current = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            var next = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            h.start(current);
            apply(h, next, Placement.NOWHERE, Map.of());
            h.runner.follow(List.of(current.id(), next.id()));
            h.runner.skipped();
            var first = h.labor.debugResident(h.level, h.body.id());
            var second = h.labor.debugResident(h.level, h.body.id());
            helper.assertTrue(first.route().size() == 2 && first.route().getFirst().spec().id().equals(current.id())
                    && first.route().get(1).spec().id().equals(next.id())
                    && first.route().stream().allMatch(work ->
                        work.spec().site().where().equals(WorldPos.of(h.level, h.at))),
                "the current work and queued work retain their sites and route order");
            helper.assertTrue(first.equals(second) && h.runner.skippedCount() == 1 && h.runner.queued().size() == 1,
                "reading diagnostics does not drain counters or alter the route");
            String json = json(h, first.lines());
            var nodes = entries(first.lines(), "node");
            helper.assertTrue(nodes.size() == 2 && arg(nodes.getFirst(), 0).equals("1")
                    && arg(nodes.get(1), 0).equals("2") && json.contains(h.at.toShortString()),
                "current work appears once and queued work retains index two for the world links");
            helper.assertTrue(!json.contains("minecraft:overworld@") && !json.contains("own_urge")
                    && !json.contains("pending_urge") && !json.contains("standing.riding")
                    && !json.contains("standing.carried_off") && !json.contains("standing.stalled")
                    && json.contains("standing.skipped"),
                "empty standing and urge fields are omitted while nonzero counters remain");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void facilityReadNamesLotClaimAndAssignedResident(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            observeStore(h);
            var node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            var stash = Stash.at(WorldPos.of(h.level, h.at));
            var iron = ItemSpec.of(Items.IRON_INGOT);
            var placement = new Placement(Set.of(), List.of(), List.of(stash), List.of(),
                List.of(new Placement.Draw(Optional.of(stash), iron, 7)));
            apply(h, node, placement,
                Map.of(node.id(), List.of(new Claim(new Resource.Lot(stash, iron), node.id(), 7))));
            h.runner.follow(List.of(node.id()));
            var debug = h.labor.debugSite(h.level, stash.pos());
            helper.assertTrue(debug.work().size() == 1
                    && debug.work().getFirst().resident().filter(h.body.id()::equals).isPresent()
                    && debug.work().getFirst().index() == 1,
                "the facility identifies the assigned resident and route index");
            String json = json(h, debug.lines());
            helper.assertTrue(json.contains("folkways.debug.overlay.claim")
                    && json.contains("item.minecraft.iron_ingot")
                    && json.contains("7") && json.contains(h.body.id().toString()),
                "the planned lot claim names its item, quantity and assigned resident");
            var drawn = debug.lines().stream().filter(line -> line instanceof Line.Goods goods
                    && goods.tint() == io.github.izakyl.folkways.core.front.api.notice.DebugText.PLANNED)
                .map(line -> (Line.Goods) line).toList();
            helper.assertTrue(drawn.size() == 1 && drawn.getFirst().stacks().equals(List.of(
                    new Line.Stack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(Items.IRON_INGOT), 7L))),
                "the store shows the seven ingots the lot claim will draw in the planned colour");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void facilityReadDetectsDirectContainerMutationWithoutRefreshingCache(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            observeStore(h);
            var snapshot = h.colony.inventory().snapshot();
            var store = snapshot.store(WorldPos.of(h.level, h.at)).orElseThrow();
            helper.assertTrue(StoreDebug.read(h.level, store, h.colony.inventory()).differences().isEmpty(),
                "unchanged contents match");
            Containers.at(h.level, h.at).orElseThrow().getItem(0).setCount(11);
            var read = StoreDebug.read(h.level, store, h.colony.inventory());
            helper.assertTrue(read.available() && read.differences().size() == 1
                    && read.differences().getFirst().cached() == 7 && read.differences().getFirst().live() == 11,
                "direct mutation reports cached seven versus live eleven");
            var card = h.labor.debugSite(h.level, store.stash().pos());
            helper.assertTrue(json(h, card.lines()).contains("folkways.debug.overlay.difference")
                    && h.colony.inventory().snapshot() == snapshot,
                "the card shows the difference without publishing a refreshed snapshot");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void facilityReadNamesTransitWithoutDrawingCachedStock(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            observeStore(h);
            var node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
            var stash = Stash.at(WorldPos.of(h.level, h.at));
            var iron = ItemSpec.of(Items.IRON_INGOT);
            apply(h, node, Placement.NOWHERE,
                Map.of(node.id(), List.of(new Claim(new Resource.Transit(stash, iron), node.id(), 11))));
            var debug = h.labor.debugSite(h.level, stash.pos());
            String json = json(h, debug.lines());
            helper.assertTrue(json.contains("item.minecraft.iron_ingot")
                    && entries(debug.lines(), "claim").size() == 1
                    && arg(entries(debug.lines(), "claim").getFirst(), 1).equals("11"),
                "future stock reservations name the item and quantity at their store");
            helper.assertTrue(debug.lines().stream().noneMatch(line -> line instanceof Line.Goods goods
                    && goods.tint() == DebugText.PLANNED),
                "transit claims do not draw the cached physical stock");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void bulkStoreReadPreservesLongCountsWithoutRefreshingSnapshot(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            var at = WorldPos.of(h.level, h.at);
            var bulk = new BulkStore(at);
            BULK.put(at, bulk);
            try {
                observeStore(h);
                var snapshot = h.colony.inventory().snapshot();
                var store = snapshot.store(at).orElseThrow();
                helper.assertTrue(StoreDebug.read(h.level, store, h.colony.inventory()).available()
                        && StoreDebug.read(h.level, store, h.colony.inventory()).differences().isEmpty(),
                    "live comparison reads the native port's aggregate entries");
                var lines = StoreDebug.lines(h.level, store, h.colony.inventory(), List.of(
                    new Claim(new Resource.Lot(store.stash(), ItemSpec.of(Items.IRON_INGOT)),
                        UUID.randomUUID(), 2_999_999_999L)));
                helper.assertTrue(lines.stream().anyMatch(line -> line instanceof Line.Goods goods
                        && goods.tint() == DebugText.PLANNED && goods.stacks().getFirst().count() == 2_999_999_999L),
                    "bulk claims render long counts without expanding synthetic slots");
                bulk.count += 5;
                var read = StoreDebug.read(h.level, store, h.colony.inventory());
                helper.assertTrue(read.differences().size() == 1
                        && read.differences().getFirst().cached() == 3_000_000_000L
                        && read.differences().getFirst().live() == 3_000_000_005L
                        && h.colony.inventory().snapshot() == snapshot,
                    "live bulk differences retain long counts without publishing an observation");
                bulk.complete = false;
                helper.assertTrue(!StoreDebug.read(h.level, store, h.colony.inventory()).available(),
                    "an incomplete bounded read is unavailable rather than a partial difference");
            } finally {
                BULK.remove(at);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void focusPacketPreservesComponentsEndpointsAndCaps(GameTestHelper helper) {
        var target = new DebugTarget.Resident(47);
        var block = new DebugTarget.Block(helper.absolutePos(BlockPos.ZERO));
        var label = Component.translatable("folkways.debug.overlay.link", 2,
            Component.translatable("item.minecraft.bread"));
        var link = new DebugFocusPacket.Link(target, block, DebugFocusPacket.Kind.DRAW, 2, label);
        var mark = new DebugFocusPacket.StoreMark(block.pos(),
            InventorySnapshot.State.DIRTY, true);
        var packet = new DebugFocusPacket(target, Collections.nCopies(60, Line.text(label)),
            Collections.nCopies(40, link), Collections.nCopies(300, mark));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            DebugFocusPacket.STREAM_CODEC.encode(buffer, packet);
            var decoded = DebugFocusPacket.STREAM_CODEC.decode(buffer);
            helper.assertTrue(decoded.equals(packet) && decoded.card().size() == 48
                    && decoded.links().size() == 32 && decoded.stores().size() == 256 && !buffer.isReadable(),
                "bounded packets preserve the target, endpoint kinds, labels, states and difference flag");
        } finally {
            buffer.release();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void eventHistoryKeepsEightBalksAndDoesNotRepeatReads(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            long now = h.level.getGameTime();
            for (int index = 0; index < 10; index++) {
                h.body.balked(new Balk(
                    Doing.open(
                        Doings.WORKING),
                    LaborRefusal.WORK_BROKE, now - 10 + index));
                h.labor.history.observe(h.body);
            }
            h.labor.history.observe(h.body);
            var lines = h.labor.history.lines(h.body.id(), now);
            helper.assertTrue(lines.size() == 8 && lines.equals(h.labor.history.lines(h.body.id(), now)),
                "the history is bounded, deduplicated and non-draining");
            h.labor.gone(h.body.id());
            helper.assertTrue(h.labor.history.lines(h.body.id(), now).isEmpty(), "departed residents leave no history");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void repeatedClaimsAndWorkAreGroupedWithoutChangingLinkIndices(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            var stash = Stash.at(WorldPos.of(h.level, h.at));
            var iron = ItemSpec.of(Items.IRON_INGOT);
            Map<UUID, Vertex> vertices = new LinkedHashMap<>();
            Map<UUID, List<Claim>> claims = new LinkedHashMap<>();
            for (int index = 0; index < 3; index++) {
                var node = h.node(Workload.Once.of(100), new AtomicInteger(), false);
                vertices.put(node.id(), new Vertex(node.id(), node, Placement.NOWHERE));
                claims.put(node.id(), List.of(
                    new Claim(new Resource.Lot(stash, iron), node.id(), 1),
                    new Claim(new Resource.Space(stash, iron), node.id(), 2),
                    new Claim(new Resource.Cargo(h.body.id(), iron), node.id(), 4),
                    new Claim(new Resource.Handed(node.id(), iron), node.id(), 8)));
            }
            apply(h, vertices, claims);
            h.runner.follow(List.copyOf(vertices.keySet()));
            var site = h.labor.debugSite(h.level, stash.pos());
            var reservations = entries(site.lines(), "claim");
            var carrying = entries(h.labor.debugResident(h.level, h.body.id()).lines(), "claim");
            helper.assertTrue(reservations.size() == 1 && arg(reservations.getFirst(), 1).equals("9")
                    && carrying.size() == 1 && arg(carrying.getFirst(), 1).equals("36")
                    && json(h, site.lines()).contains("folkways.debug.overlay.works"),
                "lot/space and cargo/handed claims sum by item, doing and resident with distinct work counts");
            var work = entries(site.lines(), "assigned");
            helper.assertTrue(work.size() == 1 && arg(work.getFirst(), 3).equals("#1,#2,#3")
                    && site.work().size() == 3 && site.work().getLast().index() == 3,
                "work rows group compact indices while world links retain every individual assignment");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void groupedSectionsKeepTenRowsAndCountRemainingGroups(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            Item[] items = {Items.IRON_INGOT, Items.GOLD_INGOT, Items.COPPER_INGOT, Items.DIAMOND,
                Items.EMERALD, Items.COAL, Items.REDSTONE, Items.LAPIS_LAZULI, Items.QUARTZ,
                Items.COBBLESTONE, Items.DIRT, Items.SAND};
            var stash = Stash.at(WorldPos.of(h.level, h.at));
            Map<UUID, Vertex> vertices = new LinkedHashMap<>();
            Map<UUID, List<Claim>> claims = new LinkedHashMap<>();
            for (Item item : items) {
                ItemSpec goods = ItemSpec.of(item);
                NodeSpec spec = NodeSpec.of(UUID.randomUUID(), HoldingGameTests.OWNER,
                    WorkSite.at(h.level, h.at), Stances.WHEREVER, Workload.Once.of(100))
                    .doing(Doings.WORKING, goods).done();
                Node node = new Node() {
                    public NodeSpec spec() {
                        return spec;
                    }

                    public Outcome commit(ServerLevel level, Worker who) {
                        return Outcome.done();
                    }
                };
                vertices.put(node.id(), new Vertex(node.id(), node, Placement.NOWHERE));
                claims.put(node.id(), List.of(new Claim(new Resource.Lot(stash, goods), node.id(), 1),
                    new Claim(new Resource.Cargo(h.body.id(), goods), node.id(), 1)));
            }
            apply(h, vertices, claims);
            h.runner.follow(List.copyOf(vertices.keySet()));
            var site = h.labor.debugSite(h.level, stash.pos());
            var resident = h.labor.debugResident(h.level, h.body.id());
            helper.assertTrue(entries(site.lines(), "claim").size() == 10
                    && entries(site.lines(), "assigned").size() == 10
                    && entries(site.lines(), "more").size() == 2
                    && entries(site.lines(), "more").stream().allMatch(line -> arg(line, 0).equals("2"))
                    && entries(resident.lines(), "claim").size() == 10
                    && entries(resident.lines(), "more").stream().anyMatch(line -> arg(line, 0).equals("2")),
                "each grouped section keeps ten rows and reports two omitted groups");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void standardHoldingExposesExecutorStageAndPositionsKeepOtherDimensions(GameTestHelper helper) {
        try (var h = new HoldingGameTests.Harness(helper)) {
            h.start(h.node(Workload.Once.of(100), new AtomicInteger(), false));
            h.labor.tick(h.runner, h.level, h.body);
            helper.assertTrue(h.runner.executionStage().filter("winding_up"::equals).isPresent(),
                "both supplied and standard wrappers delegate to the executor's real stage");
            helper.assertTrue(ColonyLabor.position(h.level, WorldPos.of(h.level, h.at))
                    .equals(h.at.toShortString())
                    && ColonyLabor.position(h.level, WorldPos.of(Level.NETHER, h.at))
                        .equals("minecraft:the_nether@" + h.at.toShortString()),
                "same-dimension positions are short and other dimensions remain qualified");
            observeStore(h);
            String store = json(h, h.labor.debugSite(h.level, WorldPos.of(h.level, h.at)).lines());
            var version = h.colony.inventory().snapshot().store(WorldPos.of(h.level, h.at)).orElseThrow().version();
            helper.assertTrue(!store.contains(version.epoch().toString()),
                "store diagnostics omit the epoch UUID");
        }
        helper.succeed();
    }

    private static List<TranslatableContents> entries(List<Line> lines, String key) {
        return lines.stream().filter(line -> line instanceof Line.Text).map(line -> ((Line.Text) line).component())
            .map(Component::getContents).filter(contents -> contents instanceof TranslatableContents)
            .map(contents -> (TranslatableContents) contents)
            .filter(contents -> contents.getKey().equals("folkways.debug.overlay." + key)).toList();
    }

    // Arguments arrive styled for the card; compare them as the words they read.
    private static String arg(TranslatableContents contents, int index) {
        Object arg = contents.getArgs()[index];
        return arg instanceof Component component ? component.getString() : String.valueOf(arg);
    }

    private static void apply(HoldingGameTests.Harness h, Map<UUID, Vertex> vertices,
                              Map<UUID, List<Claim>> claims) {
        var delta = new WeaveDelta(vertices, Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
            Set.of(), List.of(), Optional.empty());
        PlanningFixture.apply(h.labor, h.level, List.of(), delta, claims);
    }

    private static void observeStore(HoldingGameTests.Harness h) {
        h.level.setBlockAndUpdate(h.at, Blocks.BARREL.defaultBlockState());
        Containers.at(h.level, h.at).orElseThrow().setItem(0, new ItemStack(Items.IRON_INGOT, 7));
        h.colony.stores(HoldingGameTests.OWNER, h.level.dimension(), List.of(WorldPos.of(h.level, h.at)));
        var data = ColonyData.find(h.level.getServer(), h.colony.id()).orElseThrow();
        data.inventory().advance(h.level, data.works(), Map.of());
        InventoryObservations.work(
            data.inventory(), h.level.getServer(), 100);
    }

    private static void apply(HoldingGameTests.Harness h, Node node,
                              Placement placement, Map<UUID, List<Claim>> claims) {
        var delta = new WeaveDelta(Map.of(node.id(), new Vertex(node.id(), node, placement)),
            Set.of(), Set.of(), Set.of(),
            Map.of(), Map.of(), Set.of(), List.of(), Optional.empty());
        PlanningFixture.apply(h.labor, h.level, List.of(), delta, claims);
    }

    private static String json(HoldingGameTests.Harness h, List<Line> lines) {
        return lines.stream().filter(line -> line instanceof Line.Text).map(line -> (Line.Text) line)
            .map(line -> Component.Serializer.toJson(line.component(), h.level.registryAccess()))
            .collect(Collectors.joining());
    }
}
