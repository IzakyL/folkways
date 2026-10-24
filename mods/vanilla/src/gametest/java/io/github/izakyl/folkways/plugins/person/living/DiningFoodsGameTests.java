package io.github.izakyl.folkways.plugins.person.living;

import io.github.izakyl.folkways.FolkwaysMod;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningFoodsGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(DiningFoodsGameTests.class); }

    @GameTest(template = "empty")
    public static void cakePlacesOneActualItemAndFeedsSevenDistinctBites(GameTestHelper helper) {
        var level = helper.getLevel();
        var at = helper.absolutePos(new BlockPos(4, 2, 4));
        level.setBlockAndUpdate(at.below(), Blocks.STONE.defaultBlockState());
        ItemStack cakes = new ItemStack(Items.CAKE, 2);
        helper.assertTrue(DiningFoods.placeable(cakes), "cake can be chosen for a tabletop");
        helper.assertFalse(DiningFoods.placeable(new ItemStack(Items.COOKED_BEEF)), "ordinary food goes in real table slots");
        helper.assertTrue(DiningFoods.place(level, at, cakes), "real BlockItem placement succeeds");
        helper.assertValueEqual(cakes.getCount(), 1, "one actual cake was placed");
        helper.assertFalse(DiningFoods.place(level, at, cakes), "occupied tabletop is not replaced");
        helper.assertValueEqual(cakes.getCount(), 1, "failed placement consumes nothing");
        try (var fixture = new EatingFixture(helper, ItemStack.EMPTY, 0)) {
            for (int bite = 0; bite <= CakeBlock.MAX_BITES; bite++) {
                var portion = DiningFoods.serving(level, at).orElseThrow();
                helper.assertTrue(portion.item().is(Items.CAKE), "cake identity remains selectable");
                helper.assertValueEqual(portion.food().nutrition(), 2, "native cake bite nutrition");
                helper.assertTrue(DiningFoods.eat(level, at, fixture.worker).isEmpty(), "cake creates no container");
                if (bite < CakeBlock.MAX_BITES) {
                    helper.assertValueEqual(level.getBlockState(at).getValue(CakeBlock.BITES), bite + 1,
                        "one bite advances the real block exactly once");
                }
            }
            helper.assertTrue(level.getBlockState(at).isAir(), "last native bite removes the cake");
            helper.assertTrue(DiningFoods.serving(level, at).isEmpty(), "an empty tabletop provides no meal");
            boolean refused = false;
            try { DiningFoods.eat(level, at, fixture.worker); }
            catch (IllegalStateException expected) { refused = true; }
            helper.assertTrue(refused, "the consumed cake cannot feed again");
        }
        helper.succeed();
    }
}
