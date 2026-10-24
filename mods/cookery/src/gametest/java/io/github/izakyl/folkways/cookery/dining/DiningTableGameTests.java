package io.github.izakyl.folkways.cookery.dining;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.decoration.TableBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.entity.SitEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.person.living.DiningTables;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DiningTableGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(DiningTableGameTests.class); }

    @GameTest(template = "empty")
    public static void tableSelectsPlacedDishWithoutReplacingTheRealServing(GameTestHelper helper) {
        var dishId = com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry.DARK_CUISINE;
        ItemStack serving = com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry
            .getItem(dishId).getDefaultInstance();
        serving.set(DataComponents.CUSTOM_NAME, Component.literal("original serving"));
        var before = serving.copy();
        var modelState = TableFoodModels.placedDish(serving);
        helper.assertTrue(modelState != null && modelState.is(
            com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry.getBlock(dishId)),
            "the native dish uses its placed block instead of its generated item icon");
        helper.assertTrue(ItemStack.matches(serving, before), "model selection preserves the actual serving and components");
        helper.assertTrue(TableFoodModels.placedDish(new ItemStack(Items.COOKED_BEEF)) == null,
            "food without a placed dish keeps its original item model");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeDiningTableHasFourRealSingleFoodSlotsAndPreservesComponents(GameTestHelper helper) {
        CookeryDiningTables.register();
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(at, ModBlocks.TABLE_OAK.get().defaultBlockState());
        var nativeTable = (TableBlockEntity) level.getBlockEntity(at);
        var inventory = DiningTables.inventory(level, at).orElseThrow();
        ItemStack meal = new ItemStack(Items.COOKED_BEEF, 5);
        meal.set(DataComponents.CUSTOM_NAME, Component.literal("quality meal"));
        ItemStack left = Containers.insert(inventory, meal.copy());
        helper.assertValueEqual(left.getCount(), 1, "only four real portions fit");
        for (int slot = 0; slot < 4; slot++) {
            helper.assertValueEqual(nativeTable.getItems().getStackInSlot(slot).getCount(), 1, "native visible slot contains one portion");
            helper.assertTrue(ItemStack.isSameItemSameComponents(nativeTable.getItems().getStackInSlot(slot), meal), "all meal components are retained");
        }
        ItemStack actual = inventory.removeItem(0, 1);
        helper.assertTrue(ItemStack.isSameItemSameComponents(actual, meal), "eating gets the original meal data");
        helper.assertTrue(nativeTable.getItems().getStackInSlot(0).isEmpty(), "taking removes the actual displayed portion");
        helper.assertValueEqual(Containers.insert(inventory, new ItemStack(Items.STONE)).getCount(), 1, "delivery rejects nonfood");
        helper.assertTrue(Containers.at(level, at).isEmpty(), "dining display is not an ingredient stock container");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void nativeChairHoldsResidentAndReleasesOnlyDiningMount(GameTestHelper helper) {
        CookeryDiningTables.register();
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(at, ModBlocks.CHAIR_OAK.get().defaultBlockState());
        ResidentEntity resident = PersonBody.RESIDENT.get().create(level);
        resident.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 1.5);
        resident.setNoAi(true);
        level.addFreshEntity(resident);
        try {
            helper.assertTrue(DiningTables.chair(level, at), "native chair is recognized");
            helper.assertTrue(DiningTables.sit(level, at, resident), "resident uses native seat");
            SitEntity seat = (SitEntity) resident.getVehicle();
            helper.assertFalse(DiningTables.available(level, at), "another diner cannot take an occupied chair");
            helper.assertTrue(DiningTables.available(level, at, resident), "current diner keeps its own chair");
            for (int tick = 0; tick < 25; tick++) { seat.tick(); }
            helper.assertTrue(seat.isAlive() && resident.getVehicle() == seat, "native tick accepts nonplayer passengers");
            DiningTables.stand(resident);
            helper.assertFalse(resident.isPassenger(), "ending dinner releases the chair");
            var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            try {
                player.startRiding(seat, true);
                helper.assertFalse(DiningTables.available(level, at), "a player's seat is unavailable");
                helper.assertFalse(DiningTables.sit(level, at, resident), "diner cannot steal a player's seat");
                helper.assertTrue(player.getVehicle() == seat, "player keeps the native chair");
            } finally { player.stopRiding(); player.discard(); }
            for (int tick = 0; tick < 12; tick++) { seat.tick(); }
            helper.assertTrue(seat.isRemoved(), "empty native seats clean themselves up");
        } finally { DiningTables.stand(resident); resident.discard(); }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void basicDiningFurnitureDoesNotInventStorageOrMounts(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        level.setBlockAndUpdate(at, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        helper.assertTrue(DiningTables.table(level, at), "upper slab supports a basic table");
        helper.assertTrue(DiningTables.inventory(level, at).isEmpty(), "basic table has no invented inventory");
        level.setBlockAndUpdate(at, Blocks.OAK_STAIRS.defaultBlockState());
        helper.assertTrue(DiningTables.chair(level, at), "lower stairs support basic chairs");
        level.setBlockAndUpdate(at, Blocks.CRAFTING_TABLE.defaultBlockState());
        helper.assertFalse(DiningTables.table(level, at), "workstations are not dining furniture by name");
        helper.succeed();
    }
}
