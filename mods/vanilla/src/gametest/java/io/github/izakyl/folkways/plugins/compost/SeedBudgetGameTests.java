package io.github.izakyl.folkways.plugins.compost;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
    public static void exactStockTargetsProtectTheirComponentVariantDuringPlanning(GameTestHelper helper) {
        ItemStack kept = new ItemStack(Items.WHEAT_SEEDS);
        var data = new CompoundTag();
        data.putString("variety", "kept");
        kept.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        var location = SeedBudget.Location.pack(UUID.randomUUID());
        var snapshot = new SeedBudget.Snapshot(
            List.of(new SeedBudget.Stock("store", location, List.of(new ItemStack(Items.WHEAT_SEEDS, 100), kept))),
            List.of(), List.of(), Map.of(), List.of(new SeedBudget.StockTarget("special", Goods.specOf(kept), 1)),
            true);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS), item -> 0);
        helper.assertValueEqual(budget.tally(Items.WHEAT_SEEDS).compostable(), 100L,
            "planning protects the exact target while allowing ordinary seeds");
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

    private static SeedBudget budget(SeedBudget.Snapshot snapshot) {
        return new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS));
    }

    private static SeedBudget.Stock stock(String id, SeedBudget.Location location, int count) {
        ArrayList<ItemStack> stacks = new ArrayList<>();
        for (int left = count; left > 0; left -= 64) stacks.add(new ItemStack(Items.WHEAT_SEEDS, Math.min(64, left)));
        return new SeedBudget.Stock(id, location, stacks);
    }
}
