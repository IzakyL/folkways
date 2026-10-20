package io.github.izakyl.folkways.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.plugins.farming.CropSeeds;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
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

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedBudgetGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedBudgetGameTests.class); }

    @GameTest(template = "empty")
    public static void farmingCatalogueIncludesFoodRiceAndEveryActualSowingAlternative(GameTestHelper helper) {
        Set<Item> seeds = CropSeeds.catalogue(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        helper.assertTrue(seeds.containsAll(Set.of(ModItems.WILD_RICE_SEED.get(), ModItems.RICE_SEED.get(),
            ModItems.TEA_SEED.get(), ModItems.LETTUCE_SEED.get(), Items.WHEAT_SEEDS, Items.POTATO,
            Items.CARROT, Items.OAK_SAPLING)), "actual farming crops and alternative rice grains form the catalogue");
        helper.assertTrue(!seeds.contains(Items.BONE_MEAL), "compost output is not invented as a sowing seed");
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
    public static void rareFirstSeedsAndRiceFoodRemainProtectedAndMaintainTargetsKeepTheirMeaning(GameTestHelper helper) {
        Item rice = ModItems.RICE_SEED.get();
        var place = SeedBudget.Location.pack(UUID.randomUUID());
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("rare", place,
            List.of(new ItemStack(ModItems.WILD_RICE_SEED.get()), new ItemStack(rice, 64)))), List.of(), List.of(), Map.of());
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(rice, ModItems.WILD_RICE_SEED.get()));
        helper.assertValueEqual(budget.tally(rice).compostable(), 1L, "one wild seed replaces one grain in the single shared rice-purpose reserve");
        helper.assertValueEqual(budget.tally(ModItems.WILD_RICE_SEED.get()).missing(), 0L, "the rice purpose has one combined stack, not two independent stock targets");
        helper.assertValueEqual(budget.reserves().size(), 1, "wild rice and edible rice offer a single alternative stock request");
        helper.assertValueEqual(budget.tally(ModItems.WILD_RICE_SEED.get()).compostable(), 0L, "no first-batch rare seed is composted");
        SeedBudget maintained = budget(new SeedBudget.Snapshot(List.of(stock("maintained", place, 140)), List.of(), List.of(), Map.of(Items.WHEAT_SEEDS, 128L)));
        helper.assertValueEqual(maintained.tally(Items.WHEAT_SEEDS).compostable(), 12L,
            "an explicit two-stack maintain target protects 128, rather than adding a second implicit 64");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void riceSowingClaimsStayProtectedInsideTheSharedRicePurpose(GameTestHelper helper) {
        Item rice = ModItems.RICE_SEED.get();
        Item wild = ModItems.WILD_RICE_SEED.get();
        var place = SeedBudget.Location.pack(UUID.randomUUID());
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("rice nursery", place,
            List.of(new ItemStack(wild), new ItemStack(rice, 64)))),
            List.of(new SeedBudget.Claim(UUID.randomUUID(), place, Goods.specOf(rice.getDefaultInstance()), 1)), List.of(), Map.of());
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(wild, rice));
        helper.assertValueEqual(budget.tally(rice).compostable(), 0L,
            "a grain promised for actual sowing is protected before filling the one shared stock reserve");
        helper.assertValueEqual(budget.tally(wild).compostable(), 0L, "the preferred first wild seed is also preserved");
        helper.assertValueEqual(budget.reserves().getFirst().missing(), 0L, "one claimed grain plus a combined stack satisfies the purpose");
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
    public static void alternativeTargetsProtectTheirTotalOnceAndReuseThePurposeReserve(GameTestHelper helper) {
        Item wild = ModItems.WILD_RICE_SEED.get();
        Item rice = ModItems.RICE_SEED.get();
        var alternatives = io.github.izakyl.folkways.core.api.terms.ItemSpec.anyOf(List.of(
            Goods.specOf(wild.getDefaultInstance()), Goods.specOf(rice.getDefaultInstance())));
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("nursery", SeedBudget.Location.pack(UUID.randomUUID()),
            List.of(new ItemStack(wild, 64), new ItemStack(rice, 64)))), List.of(), List.of(), Map.of(),
            List.of(new SeedBudget.StockTarget("rice pantry", alternatives, 96)), true);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(wild, rice));
        long surplus = budget.tally(wild).compostable() + budget.tally(rice).compostable();
        helper.assertValueEqual(surplus, 32L, "96 alternatives share one target, reusing rather than adding the purpose reserve");
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

    private static SeedBudget budget(SeedBudget.Snapshot snapshot) {
        return new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS));
    }

    private static SeedBudget.Stock stock(String id, SeedBudget.Location location, int count) {
        java.util.ArrayList<ItemStack> stacks = new java.util.ArrayList<>();
        for (int left = count; left > 0; left -= 64) stacks.add(new ItemStack(Items.WHEAT_SEEDS, Math.min(64, left)));
        return new SeedBudget.Stock(id, location, stacks);
    }
}
