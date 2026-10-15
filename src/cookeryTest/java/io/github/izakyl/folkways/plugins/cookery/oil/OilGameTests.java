package io.github.izakyl.folkways.plugins.cookery.oil;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.EnamelBasinBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.OilPotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.OilPotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.KitchenShovelItem;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OilGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(OilGameTests.class); }

    @GameTest(template = "empty")
    public static void placedOilUsesItsOneNativeSlotAndAllTwoHundredFiftySixUnits(GameTestHelper helper) {
        PlacedOilStore.register(); var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, ModBlocks.OIL_POT.get().defaultBlockState());
        var nativePot = (OilPotBlockEntity) level.getBlockEntity(at); var store = Containers.at(level, at).orElseThrow();
        helper.assertValueEqual(store.getContainerSize(), 1, "the adapter exposes one actual native slot, not four invented slots");
        ItemStack oil = new ItemStack(ModItems.OIL.get(), 256);
        helper.assertTrue(Containers.insert(store, oil).isEmpty(), "the actual native slot accepts its full 256 capacity through the public container limit");
        helper.assertValueEqual(nativePot.getOilCount(), 256, "native BE count agrees with the actual item handler");
        helper.assertValueEqual(Goods.countIn(store, Goods.specOf(oil)), 256L, "colony inventory sees every real stored unit");
        helper.assertTrue(level.getBlockState(at).getValue(OilPotBlock.HAS_OIL), "native rendering state updates after insertion");
        helper.assertValueEqual(Containers.insert(store, new ItemStack(ModItems.OIL.get(), 3)).getCount(), 3, "a full native pot rejects overflow without consuming it");
        ItemStack drawn = Containers.extract(store, stack -> stack.is(ModItems.OIL.get()), 65);
        helper.assertValueEqual(drawn.getCount(), 64, "the native handler limits one actual extraction to the item's normal stack size");
        ItemStack last = Containers.extract(store, stack -> stack.is(ModItems.OIL.get()), 1);
        helper.assertValueEqual(last.getCount(), 1, "a second real extraction takes the remaining requested unit");
        helper.assertValueEqual(drawn.getCount() + last.getCount(), 65, "two native calls remove exactly sixty-five real stored units");
        helper.assertValueEqual(nativePot.getOilCount(), 191, "all actual native count mutations remain in sync");
        store.clearContent(); helper.assertTrue(!level.getBlockState(at).getValue(OilPotBlock.HAS_OIL), "draining the true handler clears its native oil model"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeOilReloadKeepsTheExactRemainingQuantityAndRejectsOtherItems(GameTestHelper helper) {
        PlacedOilStore.register(); var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, ModBlocks.OIL_POT.get().defaultBlockState());
        var nativePot = (OilPotBlockEntity) level.getBlockEntity(at); nativePot.setOilCount(200);
        var store = Containers.at(level, at).orElseThrow();
        helper.assertValueEqual(Containers.extract(store, stack -> true, 17).getCount(), 17, "native saved inventory is really extracted");
        var saved = nativePot.saveWithoutMetadata(level.registryAccess()); level.removeBlockEntity(at);
        var reloaded = new OilPotBlockEntity(at, level.getBlockState(at)); reloaded.loadWithComponents(saved, level.registryAccess()); level.setBlockEntity(reloaded);
        var after = Containers.at(level, at).orElseThrow(); helper.assertValueEqual(after.getItem(0).getCount(), 183, "reloading recreates only the native remaining oil");
        ItemStack refused = Containers.insert(after, new ItemStack(Items.APPLE, 4));
        helper.assertValueEqual(refused.getCount(), 4, "native oil filtering keeps unrelated items outside");
        helper.assertValueEqual(reloaded.getOilCount(), 183, "a rejected deposit does not overwrite the oil"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void directOilRemainsACompleteNativeSupplyRouteWithoutAnOilPotOrBasin(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at.below(), Blocks.MAGMA_BLOCK.defaultBlockState()); level.setBlockAndUpdate(at, ModBlocks.POT.get().defaultBlockState());
        var pot = (PotBlockEntity) level.getBlockEntity(at); var actor = PersonBody.RESIDENT.get().create(level);
        try {
            ItemStack oil = new ItemStack(ModItems.OIL.get(), 2);
            helper.assertTrue(pot.onPlaceOil(level, actor, oil), "the native pot consumes ordinary oil directly");
            helper.assertValueEqual(oil.getCount(), 1, "one actual oil unit is consumed");
            helper.assertTrue(pot.getBlockState().getValue(com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock.HAS_OIL), "no optional oil container is required to oil a real cooking pot");
        } finally { actor.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void openingABasinDoesNotSpendOilAndFillingConsumesOnlyActualFreeCapacity(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, ModBlocks.ENAMEL_BASIN.get().defaultBlockState()); ItemStack oil = new ItemStack(ModItems.OIL.get(), 35);
        helper.assertTrue(EnamelOil.interact(level, at, oil), "the first native-style click opens the lid");
        helper.assertValueEqual(oil.getCount(), 35, "opening never consumes the held actual oil");
        helper.assertTrue(EnamelOil.interact(level, at, oil), "the open basin accepts its actual oil capacity");
        helper.assertValueEqual(oil.getCount(), 3, "only the actual thirty-two free units are consumed");
        helper.assertValueEqual(level.getBlockState(at).getValue(EnamelBasinBlock.OIL_COUNT), 32, "all consumed oil is stored in the true native block property");
        helper.assertTrue(!EnamelOil.interact(level, at, oil), "a full basin cannot consume more oil"); helper.assertValueEqual(oil.getCount(), 3, "overflow remains real stock"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aRealNamedShovelTakesAndReturnsOneOilUnitWithoutCreatingOilItems(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, ModBlocks.ENAMEL_BASIN.get().defaultBlockState().setValue(EnamelBasinBlock.HAS_LID, false).setValue(EnamelBasinBlock.OIL_COUNT, 2));
        ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get()); shovel.setDamageValue(17); shovel.set(DataComponents.CUSTOM_NAME, Component.literal("Real shared shovel"));
        helper.assertTrue(EnamelOil.interact(level, at, shovel), "the actual shovel takes one real unit");
        helper.assertTrue(KitchenShovelItem.hasOil(shovel), "the native shovel component now carries that unit");
        helper.assertValueEqual(level.getBlockState(at).getValue(EnamelBasinBlock.OIL_COUNT), 1, "taking from the actual basin decrements exactly one");
        helper.assertTrue(EnamelOil.interact(level, at, shovel), "the same physical shovel returns the unit through the native reverse interaction");
        helper.assertTrue(!KitchenShovelItem.hasOil(shovel) && shovel.getDamageValue() == 17 && shovel.getHoverName().getString().equals("Real shared shovel"), "oil transfer preserves actual identity, name and wear");
        helper.assertValueEqual(level.getBlockState(at).getValue(EnamelBasinBlock.OIL_COUNT), 2, "taking then returning conserves the exact basin quantity"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void emptyAndFullBasinBoundariesNeverFlipARejectedShovelComponent(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1)); ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        level.setBlockAndUpdate(at, ModBlocks.ENAMEL_BASIN.get().defaultBlockState().setValue(EnamelBasinBlock.HAS_LID, false));
        helper.assertTrue(!EnamelOil.interact(level, at, shovel) && !KitchenShovelItem.hasOil(shovel), "an empty basin cannot create an oil-coated shovel");
        level.setBlockAndUpdate(at, level.getBlockState(at).setValue(EnamelBasinBlock.OIL_COUNT, 32)); KitchenShovelItem.setHasOil(shovel, true);
        helper.assertTrue(!EnamelOil.interact(level, at, shovel) && KitchenShovelItem.hasOil(shovel), "a full basin cannot destroy the shovel's carried oil");
        helper.assertTrue(!EnamelOil.interact(level, at, new ItemStack(Items.IRON_SHOVEL)), "native-specific kitchen shovel rules are preserved"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void basinOilOnTheActualShovelCanEnterANativeCookingPot(GameTestHelper helper) {
        var level = helper.getLevel(); BlockPos basin = helper.absolutePos(new BlockPos(1, 1, 1)); BlockPos cooking = basin.east(2);
        level.setBlockAndUpdate(basin, ModBlocks.ENAMEL_BASIN.get().defaultBlockState().setValue(EnamelBasinBlock.HAS_LID, false).setValue(EnamelBasinBlock.OIL_COUNT, 1));
        level.setBlockAndUpdate(cooking.below(), Blocks.MAGMA_BLOCK.defaultBlockState()); level.setBlockAndUpdate(cooking, ModBlocks.POT.get().defaultBlockState());
        var actor = PersonBody.RESIDENT.get().create(level); ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        try {
            helper.assertTrue(EnamelOil.interact(level, basin, shovel), "the helper loads exactly one actual oil component");
            var pot = (PotBlockEntity) level.getBlockEntity(cooking);
            helper.assertTrue(pot.onPlaceOil(level, actor, shovel), "the native cooking API consumes that actual shovel oil");
            helper.assertTrue(pot.getBlockState().getValue(com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock.HAS_OIL) && !KitchenShovelItem.hasOil(shovel), "the oil moves from the real tool into the real pot");
            helper.assertValueEqual(level.getBlockState(basin).getValue(EnamelBasinBlock.OIL_COUNT), 0, "nothing is left duplicated in the basin");
        } finally { actor.discard(); }
        helper.succeed();
    }
}
