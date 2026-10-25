package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.Release;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedPlanningGameTests.class); }

    @GameTest(template = "empty")
    public static void everySeedSharesOneSettlementOfPhysicalClaimsFutureAndTargets(GameTestHelper helper) {
        var store = SeedBudget.Location.store(WorldPos.of(helper.getLevel(), helper.absolutePos(new BlockPos(1, 1, 1))));
        var pack = SeedBudget.Location.pack(UUID.randomUUID());
        ItemSpec wheat = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        ItemSpec beet = Goods.specOf(Items.BEETROOT_SEEDS.getDefaultInstance());
        ItemSpec either = ItemSpec.anyOf(List.of(wheat, beet));
        var warehouse = new SeedBudget.Stock("one physical store", store, List.of(
            new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.WHEAT_SEEDS, 36),
            new ItemStack(Items.BEETROOT_SEEDS, 64), new ItemStack(Items.BEETROOT_SEEDS, 16)));
        var bound = new SeedBudget.Claim(UUID.randomUUID(), store, wheat, 10);
        var future = new SeedBudget.FutureNeed("one sowing", beet, 12);
        var target = new SeedBudget.StockTarget("one shared target", either, 40);
        var snapshot = new SeedBudget.Snapshot(List.of(warehouse, warehouse,
            new SeedBudget.Stock("one real pack", pack, List.of(new ItemStack(Items.MELON_SEEDS, 50)))),
            List.of(bound, bound, new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.UNKNOWN, either, 5)),
            List.of(future, future), Map.of(Items.WHEAT_SEEDS, 30L), List.of(target, target), true);
        AtomicInteger snapshots = new AtomicInteger(), catalogues = new AtomicInteger(), retention = new AtomicInteger();
        Set<Item> kinds = kinds();
        SeedBudget budget = new SeedBudget(() -> { snapshots.incrementAndGet(); return snapshot; },
            () -> { catalogues.incrementAndGet(); return kinds; }, item -> { retention.incrementAndGet(); return 8; });
        var planning = budget.planning();
        for (int station = 0; station < 32; station++) {
            helper.assertTrue(planning.tally(Items.WHEAT_SEEDS).equals(new SeedBudget.Tally(100, 15, 62, 23, 0)),
                "bound and unknown claims, future demand and distinct ordered targets keep their exact allocation");
            helper.assertTrue(planning.tally(Items.BEETROOT_SEEDS).equals(new SeedBudget.Tally(80, 12, 8, 60, 0)),
                "alternative targets do not allocate the same beet seeds twice");
            helper.assertTrue(planning.tally(Items.MELON_SEEDS).equals(new SeedBudget.Tally(50, 0, 8, 42, 0)),
                "carried seeds are one physical inventory, with the same per-purpose reserve");
        }
        helper.assertValueEqual(snapshots.get(), 1, "a complete synchronous planning batch reads physical inventory once");
        helper.assertValueEqual(catalogues.get(), 1, "the budget reads its seed catalogue once per batch");
        helper.assertValueEqual(retention.get(), 3, "all kinds settle together once, rather than once for each seed lookup");
        helper.assertValueEqual(planning.reserves().size(), 3, "all planting purposes remain visible");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void freshPermitsRejectClaimsAndSettingsChangedAfterTheReadOnlyPreview(GameTestHelper helper) {
        UUID node = UUID.randomUUID();
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        var snapshot = new AtomicReference<>(snapshot(location, 85, List.of(), true));
        var keep = new AtomicInteger(64);
        var budget = new SeedBudget(snapshot::get, () -> Set.of(Items.WHEAT_SEEDS), ignored -> keep.get());
        var preview = budget.planning();
        helper.assertValueEqual(preview.tally(Items.WHEAT_SEEDS).compostable(), 21L, "the original physical batch is affordable");
        var own = new SeedBudget.Claim(node, location, goods, 21);
        snapshot.set(snapshot(location, 64, List.of(own), true));
        helper.assertTrue(budget.permits(node, new ItemStack(Items.WHEAT_SEEDS, 21)),
            "live settlement restores real escrow and excludes only its own input claim");
        snapshot.set(snapshot(location, 64, List.of(own,
            new SeedBudget.Claim(UUID.randomUUID(), location, goods, 1)), true));
        helper.assertTrue(!budget.permits(node, new ItemStack(Items.WHEAT_SEEDS, 21)),
            "a new same-tick sowing claim cannot consume the old preview allowance");
        snapshot.set(snapshot(location, 64, List.of(own), true));
        keep.set(65);
        helper.assertTrue(!budget.permits(node, new ItemStack(Items.WHEAT_SEEDS, 21)),
            "a same-tick configuration increase is reread before destructive commit");
        helper.assertValueEqual(preview.tally(Items.WHEAT_SEEDS).compostable(), 21L, "the earlier read-only report stays immutable");
        helper.assertValueEqual(budget.planning().tally(Items.WHEAT_SEEDS).compostable(), 0L, "a new planning call reads current data");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reserveRequestsReadOneSnapshotAndSeeTheNextSameTickChange(GameTestHelper helper) {
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        AtomicInteger snapshots = new AtomicInteger(), catalogues = new AtomicInteger(), retention = new AtomicInteger();
        var current = new AtomicReference<>(new SeedBudget.Snapshot(List.of(), List.of(), List.of(), Map.of()));
        var budget = new SeedBudget(() -> { snapshots.incrementAndGet(); return current.get(); },
            () -> { catalogues.incrementAndGet(); return kinds(); }, item -> { retention.incrementAndGet(); return 8; });
        var first = budget.reserves();
        helper.assertValueEqual(first.size(), 3, "all known seed purposes produce their real shortfall");
        helper.assertTrue(first.stream().allMatch(reserve -> reserve.missing() == 8), "empty inventory lacks exactly its configured reserve");
        helper.assertValueEqual(snapshots.get(), 1, "reserves does not reread inventory for each planting purpose");
        helper.assertValueEqual(catalogues.get(), 1, "reserves reads one catalogue");
        helper.assertValueEqual(retention.get(), 3, "one allocation pass covers every reserve request");
        current.set(new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("live pack", location,
            List.of(new ItemStack(Items.WHEAT_SEEDS, 8), new ItemStack(Items.BEETROOT_SEEDS, 8), new ItemStack(Items.MELON_SEEDS, 8)))),
            List.of(), List.of(), Map.of()));
        helper.assertTrue(budget.reserves().stream().allMatch(reserve -> reserve.missing() == 0), "the next call sees all newly arrived stock");
        helper.assertValueEqual(snapshots.get(), 2, "there is no cache shared by successive calls in the same tick");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void incompletePlanningRemainsConservativeAndCatalogueChangesDoNotReuseOldResults(GameTestHelper helper) {
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var current = new AtomicReference<>(snapshot(location, 100, List.of(), false));
        var catalogue = new AtomicReference<Set<Item>>(Set.of(Items.WHEAT_SEEDS));
        var budget = new SeedBudget(current::get, catalogue::get);
        var unknown = budget.planning();
        helper.assertTrue(!unknown.complete() && unknown.reserves().isEmpty(), "unknown stock cannot create speculative reserve requests");
        helper.assertTrue(unknown.tally(Items.WHEAT_SEEDS).physical() == 100
            && unknown.tally(Items.WHEAT_SEEDS).compostable() == 0, "known physical stock is reported but cannot be destroyed");
        helper.assertTrue(!budget.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS)), "escrow cannot bypass an incomplete live snapshot");
        current.set(new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("now loaded", location,
            List.of(new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.BEETROOT_SEEDS, 64), new ItemStack(Items.BEETROOT_SEEDS, 8)))),
            List.of(), List.of(), Map.of()));
        catalogue.set(Set.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS));
        var complete = budget.planning();
        helper.assertTrue(complete.complete(), "the next planning call observes newly loaded inventory");
        helper.assertValueEqual(complete.tally(Items.BEETROOT_SEEDS).compostable(), 8L, "a newly admitted seed kind is included immediately");
        helper.assertValueEqual(unknown.tally(Items.BEETROOT_SEEDS).physical(), 0L, "the old immutable report acquires no new catalogue entries");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void oneStationSweepSharesStockAndSeparateCataloguesAcrossThirtyTwoBuckets(GameTestHelper helper) {
        var level = helper.getLevel();
        AtomicInteger snapshots = new AtomicInteger(), budgetCatalogues = new AtomicInteger(), stationCatalogues = new AtomicInteger();
        AtomicInteger retention = new AtomicInteger();
        var stock = snapshot(SeedBudget.Location.pack(UUID.randomUUID()), 200, List.of(), true);
        var budget = new SeedBudget(() -> { snapshots.incrementAndGet(); return stock; },
            () -> { budgetCatalogues.incrementAndGet(); return kinds(); }, item -> { retention.incrementAndGet(); return 8; });
        var station = new CompostStation(ignored -> Optional.of(budget),
            () -> { stationCatalogues.incrementAndGet(); return kinds(); });
        UUID colony = UUID.randomUUID();
        var bookings = new CompostBookings();
        var planning = station.planning(colony);
        for (int index = 0; index < 32; index++) {
            BlockPos pos = helper.absolutePos(new BlockPos(1 + index % 8, 1, 1 + index / 8));
            level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
            var at = WorldPos.of(level, pos);bookings.admit(at);
            var goals = station.recovery(colony, level, at, Stances.WHEREVER, bookings, () -> { }, planning);
            helper.assertValueEqual(goals.size(), 1, "every eligible bucket still offers its original feed task");
            helper.assertTrue(goals.getFirst().nodes().getFirst().spec().needs().getFirst().spec()
                .equals(Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance())), "empty earlier kinds do not hide the affordable last seed");
        }
        helper.assertValueEqual(snapshots.get(), 1, "all thirty-two buckets share one physical inventory read");
        helper.assertValueEqual(budgetCatalogues.get(), 1, "the allowance catalogue is read once");
        helper.assertValueEqual(stationCatalogues.get(), 1, "the distinct station candidate supplier is also read once");
        helper.assertValueEqual(retention.get(), 3, "one settlement covers all seeds, not thirty-two times three settlements");
        BlockPos pos = helper.absolutePos(new BlockPos(1, 1, 1));
        station.recovery(colony, level, WorldPos.of(level, pos), Stances.WHEREVER, bookings, () -> { }, station.planning(colony));
        helper.assertValueEqual(snapshots.get(), 2, "a subsequent goals sweep creates a fresh batch even without advancing a tick");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void matureAndDisabledBucketsDoNotReadAnySeedInventory(GameTestHelper helper) {
        var level = helper.getLevel();
        var pos = helper.absolutePos(new BlockPos(1, 1, 1));
        var at = WorldPos.of(level, pos);
        var station = new CompostStation(ignored -> { throw new AssertionError("mature/disabled work cannot read seed stock"); },
            () -> { throw new AssertionError("mature/disabled work cannot read seed catalogue"); }, ignored -> false);
        UUID colony = UUID.randomUUID();var bookings = new CompostBookings();bookings.admit(at);
        level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState().setValue(ComposterBlock.LEVEL, 8), Block.UPDATE_ALL);
        helper.assertValueEqual(station.recovery(colony, level, at, Stances.WHEREVER, bookings, () -> { }, station.planning(colony)).size(), 1,
            "mature output remains collectable without ever creating a seed batch");
        level.setBlock(pos, Blocks.COMPOSTER.defaultBlockState(), Block.UPDATE_ALL);
        helper.assertTrue(station.recovery(colony, level, at, Stances.WHEREVER, bookings, () -> { }, station.planning(colony)).isEmpty(),
            "disabled empty buckets do not scan inventory merely because goals was asked");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void realStoresAcrossDimensionsAndLocalPacksRefreshBetweenCalls(GameTestHelper helper) {
        var level = helper.getLevel();var server = level.getServer();var nether = server.getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "the normal test server provides its other dimension");
        var colony = Colonies.mint(server);var bodies = new ArrayList<ResidentEntity>();
        var storesOwner = ResourceLocation.fromNamespaceAndPath("folkways", "test/seed_planning_stores");
        BlockPos left = helper.absolutePos(new BlockPos(2, 1, 2));
        var leftState = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH).setValue(ChestBlock.TYPE, ChestType.LEFT);
        BlockPos right = left.relative(ChestBlock.getConnectedDirection(leftState));
        BlockPos elsewhere = new BlockPos(left.getX(), 64, left.getZ());
        try {
            int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
            level.setBlock(left, leftState, flags);level.setBlock(right, leftState.setValue(ChestBlock.TYPE, ChestType.RIGHT), flags);
            nether.getChunk(elsewhere);nether.setBlock(elsewhere, Blocks.BARREL.defaultBlockState(), Block.UPDATE_ALL);
            var sharedChest = Containers.at(level, left).orElseThrow();
            sharedChest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));sharedChest.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 36));
            var remote = Containers.at(nether, elsewhere).orElseThrow();remote.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            colony.stores(storesOwner, level.dimension(), List.of(WorldPos.of(level, left), WorldPos.of(level, right)));
            colony.stores(storesOwner, nether.dimension(), List.of(WorldPos.of(nether, elsewhere)));
            for (int index = 0; index < 2; index++) {
                ResidentEntity body = PersonBody.RESIDENT.get().create(level);body.setNoAi(true);
                body.moveTo(helper.absolutePos(new BlockPos(5 + index, 1, 4)).getCenter());
                helper.assertTrue(level.addFreshEntity(body), "each local fixture resident is admitted to the actual level");
                helper.assertTrue(level.getEntity(body.getUUID()) == body, "both local residents are indexed before reading physical stock");
                colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();body.joinColony(colony.id());colony.entered(body);
                body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, index == 0 ? 20 : 11));bodies.add(body);
            }
            var support = new SeedSupport(colony, server);var budget = support.budget(level);
            var before = budget.planning();
            helper.assertTrue(before.complete(), "the double chest, actual Nether barrel and two local resident packs are all observed");
            helper.assertValueEqual(before.tally(Items.WHEAT_SEEDS).physical(), 195L,
                "the double chest counts once, plus one real other-dimension store and both local packs");
            bodies.getFirst().pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 12));
            remote.setItem(1, new ItemStack(Items.WHEAT_SEEDS, 8));
            helper.assertValueEqual(budget.planning().tally(Items.WHEAT_SEEDS).physical(), 195L,
                "a same-tick transfer from a local pack to a Nether store is counted once at its current location");
            remote.setItem(1, ItemStack.EMPTY);
            helper.assertValueEqual(budget.planning().tally(Items.WHEAT_SEEDS).physical(), 187L,
                "the next batch sees removal of those eight remote seeds without waiting a tick");
            helper.assertValueEqual(support.budget(nether).planning().tally(Items.WHEAT_SEEDS).physical(), 187L,
                "a query from the other dimension uses the same complete colony inventory");
            helper.assertValueEqual(before.tally(Items.WHEAT_SEEDS).physical(), 195L, "old planning is immutable observation only");
            CompostSettings.reserve(colony, Items.WHEAT_SEEDS, 200);
            helper.assertValueEqual(budget.planning().tally(Items.WHEAT_SEEDS).compostable(), 0L,
                "a newly configured reserve applies to the next real inventory batch");
            helper.assertTrue(!budget.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS)), "fresh execution cannot use the stale pre-change report");
            // A held resident goes unloaded the way it does in play: its chunk unloads it after it joined.
            ResidentEntity away = PersonBody.RESIDENT.get().create(level);away.setNoAi(true);
            away.moveTo(helper.absolutePos(new BlockPos(7, 1, 4)).getCenter());
            helper.assertTrue(level.addFreshEntity(away), "the resident that will be unloaded is admitted to the actual level");
            var unloaded = colony.hold(PersonContent.ID, Held.Entity.of(away)).orElseThrow();
            away.joinColony(colony.id());colony.entered(away);
            away.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            helper.assertTrue(colony.holdingOf(away.getUUID()).isPresent(), "unloading keeps the resident held");
            var unknown = budget.planning();
            helper.assertTrue(!unknown.complete() && unknown.tally(Items.WHEAT_SEEDS).compostable() == 0
                && unknown.reserves().isEmpty(), "an unloaded held resident remains a conservative unknown for the colony");
            colony.release(unloaded.id(), Release.LET_GO);
            helper.assertTrue(budget.planning().complete(), "removing the unloaded resident restores a fully observed colony");
            var absent = new BlockPos(25_000_000, 80, 25_000_000);
            colony.stores(storesOwner, level.dimension(), List.of(WorldPos.of(level, absent)));
            helper.assertTrue(!budget.planning().complete() && !level.isLoaded(absent), "unknown stores neither become disposable nor load remote chunks");
        } finally {
            bodies.forEach(ResidentEntity::discard);colony.raze();
            level.setBlock(left, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);level.setBlock(right, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            nether.setBlock(elsewhere, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    private static Set<Item> kinds() {
        return new LinkedHashSet<>(List.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS, Items.MELON_SEEDS));
    }

    private static SeedBudget.Snapshot snapshot(SeedBudget.Location location, int wheat, List<SeedBudget.Claim> claims, boolean complete) {
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = wheat; left > 0; left -= 64) stacks.add(new ItemStack(Items.WHEAT_SEEDS, Math.min(left, 64)));
        return new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("one real pack", location, stacks)), claims, List.of(), Map.of(), List.of(), complete);
    }
}
