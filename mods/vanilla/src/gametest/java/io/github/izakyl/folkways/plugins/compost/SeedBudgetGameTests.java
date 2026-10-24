package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.plugins.compost.SeedBudget;
import io.github.izakyl.folkways.core.api.terms.Goods;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.component.CustomData;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedBudgetGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedBudgetGameTests.class); }

    @GameTest(template = "empty")
    public static void exactStockTargetsProtectOnlyTheirActualComponentVariant(GameTestHelper helper) {
        ItemStack kept = new ItemStack(Items.WHEAT_SEEDS);
        var data = new CompoundTag();
        data.putString("variety", "kept");
        kept.set(DataComponents.CUSTOM_DATA,
            CustomData.of(data));
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("store", location,
            List.of(new ItemStack(Items.WHEAT_SEEDS, 100)))), List.of(), List.of(), Map.of(),
            List.of(new SeedBudget.StockTarget("special seeds", Goods.specOf(kept), 1)), true);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS), item -> 0);
        helper.assertTrue(!budget.permits(UUID.randomUUID(), kept),
            "surplus of another component variant cannot make reserved escrow compostable");
        helper.assertTrue(budget.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS)),
            "the exact target does not reserve ordinary seeds of the same item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void duplicateInventoryViewsAndDuplicateNodeClaimsNeverIncreaseTheAllowance(GameTestHelper helper) {
        var place = SeedBudget.Location.pack(UUID.randomUUID());
        var stock = stock("one real pack", place, 100);
        UUID node = UUID.randomUUID();
        var claim = new SeedBudget.Claim(node, place, Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance()), 20);
        SeedBudget budget = budget(new SeedBudget.Snapshot(List.of(stock, stock), List.of(claim, claim), List.of(), Map.of()));
        var tally = budget.tally(Items.WHEAT_SEEDS);
        helper.assertValueEqual(tally.physical(), 100L, "two views describe a single physical stack total");
        helper.assertValueEqual(tally.committed(), 20L, "one node's repeated observation is one claim");
        helper.assertValueEqual(tally.compostable(), 16L, "only unclaimed surplus above one actual seed stack is available");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void storeAndTransitClaimsClampToTheirActualPhysicalLocations(GameTestHelper helper) {
        var warehouse = SeedBudget.Location.pack(UUID.randomUUID());
        var transit = SeedBudget.Location.pack(UUID.randomUUID());
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        SeedBudget budget = budget(new SeedBudget.Snapshot(List.of(stock("warehouse", warehouse, 50), stock("transit", transit, 50)),
            List.of(new SeedBudget.Claim(UUID.randomUUID(), warehouse, goods, 80),
                new SeedBudget.Claim(UUID.randomUUID(), transit, goods, 30)), List.of(), Map.of()));
        var tally = budget.tally(Items.WHEAT_SEEDS);
        helper.assertValueEqual(tally.physical(), 100L, "claims and transit do not create any additional goods");
        helper.assertValueEqual(tally.committed(), 80L, "the old warehouse draw clamps at fifty, plus thirty real carried seeds");
        helper.assertValueEqual(tally.compostable(), 0L, "in-flight seeds and the one-stack reserve are protected");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unknownClaimsSowingAndExplicitOrdersAreProtectedBeforeComposting(GameTestHelper helper) {
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        SeedBudget budget = budget(new SeedBudget.Snapshot(List.of(stock("pack", SeedBudget.Location.pack(UUID.randomUUID()), 150)),
            List.of(new SeedBudget.Claim(UUID.randomUUID(), SeedBudget.Location.UNKNOWN, goods, 20)),
            List.of(new SeedBudget.FutureNeed("new sowing cells", goods, 10), new SeedBudget.FutureNeed("new sowing cells", goods, 10),
                new SeedBudget.FutureNeed("explicit order", goods, 5)), Map.of()));
        var tally = budget.tally(Items.WHEAT_SEEDS);
        helper.assertValueEqual(tally.committed(), 35L, "unknown claims and distinct outstanding requests reserve real units once");
        helper.assertValueEqual(tally.compostable(), 51L, "compost only sees the remaining surplus after sowing, orders and reserve");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void commitRechecksEscrowAndProtectsSeedsClaimedSincePlanning(GameTestHelper helper) {
        UUID current = UUID.randomUUID();
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        var snapshots = new AtomicReference<>(new SeedBudget.Snapshot(List.of(stock("actual pack", location, 64)),
            List.of(new SeedBudget.Claim(current, location, goods, 1)), List.of(), Map.of()));
        var budget = new SeedBudget(snapshots::get, () -> Set.of(Items.WHEAT_SEEDS));
        helper.assertTrue(budget.permits(current, new ItemStack(Items.WHEAT_SEEDS)),
            "one physically drawn escrow plus sixty-four stored seeds gives exactly one spendable seed");
        snapshots.set(new SeedBudget.Snapshot(List.of(stock("actual pack", location, 64)),
            List.of(new SeedBudget.Claim(UUID.randomUUID(), location, goods, 1)), List.of(), Map.of()));
        helper.assertTrue(!budget.permits(current, new ItemStack(Items.WHEAT_SEEDS)),
            "a new sowing claim consumes that final allowance before compost commits");
        snapshots.set(new SeedBudget.Snapshot(List.of(stock("actual pack", location, 63)), List.of(), List.of(), Map.of()));
        helper.assertTrue(!budget.permits(current, new ItemStack(Items.WHEAT_SEEDS)), "the final reserve seed is never spent");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void distinctTargetsAddButRepeatedMetadataDoesNot(GameTestHelper helper) {
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        var first = new SeedBudget.StockTarget("first pantry", goods, 80);
        var second = new SeedBudget.StockTarget("second pantry", goods, 80);
        var snapshot = new SeedBudget.Snapshot(List.of(stock("store", SeedBudget.Location.pack(UUID.randomUUID()), 200)),
            List.of(), List.of(), Map.of(), List.of(first, first, second), true);
        helper.assertValueEqual(budget(snapshot).tally(Items.WHEAT_SEEDS).compostable(), 40L,
            "two real target boxes retain 160 seeds while one repeated view does not allocate them twice");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void unloadedPhysicalInventoryStopsDestructiveWorkAndSpeculativeRestocking(GameTestHelper helper) {
        var snapshot = new SeedBudget.Snapshot(List.of(stock("loaded pack", SeedBudget.Location.pack(UUID.randomUUID()), 100)),
            List.of(), List.of(), Map.of(), List.of(), false);
        var budget = budget(snapshot);
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).physical(), 100L, "loaded physical seeds remain visible");
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), 0L, "unloaded inventory is never assumed disposable");
        helper.assertTrue(budget.reserves().isEmpty(), "no speculative seed request is made from an incomplete snapshot");
        helper.assertTrue(!budget.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS)), "escrow cannot bypass unknown stock protection");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void configuredDefaultAndIndividualRetentionUpdateWithoutRebuildingTheBudget(GameTestHelper helper) {
        var place = SeedBudget.Location.pack(UUID.randomUUID());
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("two seed kinds", place,
            List.of(new ItemStack(Items.WHEAT_SEEDS, 64), new ItemStack(Items.BEETROOT_SEEDS, 64)))),
            List.of(), List.of(), Map.of());
        var defaults = new AtomicInteger(32);
        Map<Item, Integer> overrides = new HashMap<>();
        overrides.put(Items.BEETROOT_SEEDS, 12);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS, Items.BEETROOT_SEEDS),
            item -> overrides.getOrDefault(item, defaults.get()));
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), 32L,
            "an unconfigured seed uses the configured default reserve");
        helper.assertValueEqual(budget.tally(Items.BEETROOT_SEEDS).compostable(), 52L,
            "a per-seed override replaces rather than adds to the default");
        defaults.set(48);
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), 16L,
            "changing the default applies to the next live allowance query");
        helper.assertValueEqual(budget.tally(Items.BEETROOT_SEEDS).compostable(), 52L,
            "changing the default preserves an individual override");
        overrides.put(Items.BEETROOT_SEEDS, 0);
        helper.assertValueEqual(budget.tally(Items.BEETROOT_SEEDS).compostable(), 64L,
            "an explicit zero allows every otherwise unclaimed seed");
        overrides.remove(Items.BEETROOT_SEEDS);
        helper.assertValueEqual(budget.tally(Items.BEETROOT_SEEDS).compostable(), 16L,
            "clearing an override restores the current default");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void zeroRetentionStillProtectsSowingClaimsFutureNeedsAndPlayerOrders(GameTestHelper helper) {
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        var snapshot = new SeedBudget.Snapshot(List.of(stock("seed store", location, 100)),
            List.of(new SeedBudget.Claim(UUID.randomUUID(), location, goods, 10)),
            List.of(new SeedBudget.FutureNeed("planned sowing", goods, 5)), Map.of(Items.WHEAT_SEEDS, 30L));
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS), ignored -> 0);
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), 55L,
            "zero retention still leaves ten claimed, five future sowing and thirty ordered seeds intact");
        var afterDraw = new SeedBudget.Snapshot(List.of(stock("seed store", location, 44)),
            snapshot.claims(), snapshot.future(), snapshot.maintainTargets());
        var drawnBudget = new SeedBudget(() -> afterDraw, () -> Set.of(Items.WHEAT_SEEDS), ignored -> 0);
        helper.assertTrue(!drawnBudget.permits(UUID.randomUUID(), new ItemStack(Items.WHEAT_SEEDS, 56)),
            "escrow cannot consume protected sowing and player orders even with zero baseline");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void increasedRetentionRejectsPreviouslyAffordableEscrow(GameTestHelper helper) {
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        // Eighty-five seeds existed before logistics drew a twenty-one-seed compost batch.
        var snapshot = new SeedBudget.Snapshot(List.of(stock("remaining pack", location, 64)),
            List.of(), List.of(), Map.of());
        var keep = new AtomicInteger(64);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS), ignored -> keep.get());
        UUID action = UUID.randomUUID();
        helper.assertTrue(budget.permits(action, new ItemStack(Items.WHEAT_SEEDS, 21)),
            "the original sixty-four reserve allows the physically drawn batch");
        keep.set(65);
        helper.assertTrue(!budget.permits(action, new ItemStack(Items.WHEAT_SEEDS, 21)),
            "a fresh larger reserve blocks the stale batch before destructive commit");
        helper.assertValueEqual(budget.reserves().getFirst().count(), 65L,
            "reserve requests use the same configured count as the compost allowance");
        helper.assertValueEqual(budget.reserves().getFirst().missing(), 1L,
            "the changed reserve exposes the real one-seed shortage");
        helper.succeed();
    }

    private static SeedBudget budget(SeedBudget.Snapshot snapshot) {
        return new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS));
    }

    private static SeedBudget.Stock stock(String id, SeedBudget.Location location, int count) {
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= 64) stacks.add(new ItemStack(Items.WHEAT_SEEDS, Math.min(64, left)));
        return new SeedBudget.Stock(id, location, stacks);
    }
}
