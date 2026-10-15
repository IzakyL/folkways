package io.github.izakyl.folkways.plugins.cookery.racks;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class RackContainerGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(RackContainerGameTests.class);
    }

    @GameTest(template = "empty")
    public static void realRackSlotsKeepOneToolEachAndPreserveItsComponents(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("Shared kitchen knife"));
        knife.setDamageValue(17);
        ItemStack shovel = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(Containers.insert(view, knife).isEmpty(), "the named knife goes into the real left slot");
        helper.assertTrue(Containers.insert(view, shovel).isEmpty(), "the shovel goes into the real right slot");
        helper.assertTrue(ItemStack.matches(rack.getItemLeft(), knife), "the BE itself owns the original knife components");
        helper.assertTrue(ItemStack.matches(rack.getItemRight(), shovel), "the BE itself owns the original shovel");
        ItemStack spare = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        helper.assertTrue(ItemStack.matches(Containers.insert(view, spare), spare), "a full rack refuses a third tool");
        helper.assertTrue(ItemStack.matches(Containers.extract(view, stack -> stack.is(knife.getItem()), 64), knife),
            "a real extraction returns one named, worn tool, even with a larger requested count");
        helper.assertTrue(rack.getItemLeft().isEmpty(), "the actual BE left slot empties");
        helper.assertTrue(ItemStack.matches(rack.getItemRight(), shovel), "the other physical slot is unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rackRejectsFoodAndArmorWithoutChangingEitherRealSlot(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        Containers.insert(view, knife);
        CompoundTag before = rack.saveWithFullMetadata(helper.getLevel().registryAccess());
        for (ItemStack refused : new ItemStack[] {
            new ItemStack(Items.BREAD, 3), new ItemStack(ModItems.STRAW_HAT.get())
        }) {
            helper.assertTrue(ItemStack.matches(Containers.insert(view, refused), refused),
                "the adapter obeys the native rack's tools-only restriction");
        }
        helper.assertTrue(before.equals(rack.saveWithFullMetadata(helper.getLevel().registryAccess())),
            "refused supplies did not alter either native rack slot");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fullRackFallsBackToOrdinaryStorageWithoutLosingTools(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer view = new RackContainer(rack);
        SimpleContainer ordinary = new SimpleContainer(2);
        Containers.insert(view, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        Containers.insert(view, new ItemStack(ModItems.KITCHEN_SHOVEL.get()));
        ItemStack spare = new ItemStack(ModItems.SICKLE.get());
        ItemStack left = Containers.insert(view, spare);
        helper.assertTrue(Containers.insert(ordinary, left).isEmpty(), "a full rack leaves the whole tool for ordinary storage");
        helper.assertTrue(ItemStack.matches(ordinary.getItem(0), spare), "the fallback owns exactly the original spare");
        helper.assertValueEqual(rack.getItemLeft().getCount() + rack.getItemRight().getCount()
            + ordinary.getItem(0).getCount(), 3, "both native rack slots and the fallback conserve all three tools");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void reloadedRackKeepsItsToolButAnOldViewCannotTakeOrInsertAnything(GameTestHelper helper) {
        KitchenwareRacksBlockEntity rack = rack(helper);
        RackContainer stale = new RackContainer(rack);
        ItemStack knife = new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get());
        knife.set(DataComponents.CUSTOM_NAME, Component.literal("Reloaded knife"));
        knife.setDamageValue(23);
        Containers.insert(stale, knife);
        CompoundTag saved = rack.saveWithFullMetadata(helper.getLevel().registryAccess());
        helper.getLevel().removeBlockEntity(rack.getBlockPos());
        KitchenwareRacksBlockEntity loaded = new KitchenwareRacksBlockEntity(rack.getBlockPos(), rack.getBlockState());
        loaded.loadWithComponents(saved, helper.getLevel().registryAccess());
        helper.getLevel().setBlockEntity(loaded);
        helper.assertTrue(Containers.extract(stale, stack -> true, 1).isEmpty(), "an obsolete view cannot steal the reloaded tool");
        ItemStack extra = new ItemStack(ModItems.KITCHEN_SHOVEL.get());
        helper.assertTrue(ItemStack.matches(Containers.insert(stale, extra), extra), "an obsolete view rejects every incoming tool");
        RackContainer current = new RackContainer(loaded);
        helper.assertTrue(ItemStack.matches(Containers.extract(current, stack -> true, 1), knife),
            "the real reloaded BE preserves and releases the original named, worn tool exactly once");
        helper.assertTrue(current.isEmpty(), "the newly loaded rack is physically empty after extraction");
        helper.succeed();
    }

    private static KitchenwareRacksBlockEntity rack(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(2, 1, 2));
        helper.getLevel().setBlockAndUpdate(at, ModBlocks.KITCHENWARE_RACKS.get().defaultBlockState());
        return (KitchenwareRacksBlockEntity) helper.getLevel().getBlockEntity(at);
    }
}
