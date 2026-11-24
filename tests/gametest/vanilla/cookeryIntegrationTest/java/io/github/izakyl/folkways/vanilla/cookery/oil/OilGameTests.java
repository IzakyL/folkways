package io.github.izakyl.folkways.vanilla.cookery.oil;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.OilPotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.OilPotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.shell.cookery.oil.PlacedOilStore;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class OilGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(OilGameTests.class); }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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

    @GameTest(template = "empty", templateNamespace = "folkways_core")
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
}
