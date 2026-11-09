package io.github.izakyl.folkways.cookery.seeds;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.vanilla.compost.SeedBudget;
import io.github.izakyl.folkways.vanilla.farming.CropSeeds;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
@GameTestHolder("folkways_cookery")
@PrefixGameTestTemplate(false)
public final class SeedBudgetGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedBudgetGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void farmingCatalogueIncludesFoodRiceAndEveryActualSowingAlternative(GameTestHelper helper) {
        Set<Item> seeds = CropSeeds.catalogue(helper.getLevel(), helper.absolutePos(BlockPos.ZERO));
        helper.assertTrue(seeds.containsAll(Set.of(ModItems.WILD_RICE_SEED.get(), ModItems.RICE_SEED.get(),
            ModItems.TEA_SEED.get(), ModItems.LETTUCE_SEED.get(), Items.WHEAT_SEEDS, Items.POTATO,
            Items.CARROT, Items.OAK_SAPLING)), "actual farming crops and alternative rice grains form the catalogue");
        helper.assertTrue(!seeds.contains(Items.BONE_MEAL), "compost output is not invented as a sowing seed");
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void alternativeTargetsProtectTheirTotalOnceAndReuseThePurposeReserve(GameTestHelper helper) {
        Item wild = ModItems.WILD_RICE_SEED.get();
        Item rice = ModItems.RICE_SEED.get();
        var alternatives = io.github.izakyl.folkways.core.item.api.ItemSpec.anyOf(List.of(
            Goods.specOf(wild.getDefaultInstance()), Goods.specOf(rice.getDefaultInstance())));
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("nursery", SeedBudget.Location.pack(UUID.randomUUID()),
            List.of(new ItemStack(wild, 64), new ItemStack(rice, 64)))), List.of(), List.of(), Map.of(),
            List.of(new SeedBudget.StockTarget("rice pantry", alternatives, 96)), true);
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(wild, rice));
        long surplus = budget.tally(wild).compostable() + budget.tally(rice).compostable();
        helper.assertValueEqual(surplus, 32L, "96 alternatives share one target, reusing rather than adding the purpose reserve");
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
