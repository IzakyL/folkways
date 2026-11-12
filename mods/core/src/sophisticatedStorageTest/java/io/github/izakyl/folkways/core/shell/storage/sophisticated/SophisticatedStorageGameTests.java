package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.StoreRule;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.core.shell.storage.ProxyChecks;
import net.p3pp3rf1y.sophisticatedstorage.block.ControllerBlockEntity;
import net.p3pp3rf1y.sophisticatedstorage.block.StorageIOBlockEntity;
import java.util.List;
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
import net.p3pp3rf1y.sophisticatedstorage.block.StorageBlockEntity;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class SophisticatedStorageGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SophisticatedStorageGameTests.class);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void nativeShapesAndAtomicBatches(GameTestHelper helper) {
        int x = 1;
        for (String name : List.of("chest", "barrel", "shulker_box", "limited_barrel_1", "limited_barrel_4")) {
            BlockPos pos = new BlockPos(x++, 2, 1);
            PortChecks.place(helper, pos, "sophisticatedstorage:" + name);
            PortChecks.roundTrip(helper, pos, false);
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void upgradesAndFilters(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        StorageBlockEntity storage = (StorageBlockEntity) PortChecks.place(helper, pos,
            "sophisticatedstorage:diamond_chest");
        SophisticatedChecks.upgrades(helper, pos, storage.getStorageWrapper(), "sophisticatedstorage");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void bulkReadUsesLongAndKeepsNativeStackCaps(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        StorageBlockEntity storage = (StorageBlockEntity) PortChecks.place(helper, pos, "sophisticatedstorage:chest");
        var wrapper = storage.getStorageWrapper();
        wrapper.getUpgradeHandler().setStackInSlot(0, PortChecks.item("sophisticatedstorage:stack_upgrade_omega_tier"));
        wrapper.getInventoryHandler().setStackInSlot(0, new ItemStack(Items.STONE, 1_500_000_000));
        wrapper.getInventoryHandler().setStackInSlot(1, new ItemStack(Items.STONE, 1_500_000_000));
        helper.assertTrue(PortChecks.count(helper, PortChecks.at(helper, pos)) == 3_000_000_000L,
            "equal keys are summed without int overflow");
        wrapper.getInventoryHandler().setStackInSlot(0, ItemStack.EMPTY);
        wrapper.getInventoryHandler().setStackInSlot(1, ItemStack.EMPTY);
        wrapper.getUpgradeHandler().setStackInSlot(0, PortChecks.item("sophisticatedstorage:stack_downgrade_tier_1"));
        WorldPos at = WorldPos.of(helper.getLevel(), helper.absolutePos(pos));
        List<StoreRule> oneSlot = List.of(new StoreRule.Slots(at, 0, 1));
        helper.assertTrue(!PortChecks.at(helper, pos).put(helper.getLevel(),
            List.of(new ItemStack(Items.ENDER_PEARL, 64)), oneSlot, false, false).done(),
            "native per-item stack caps are enforced");
        Room.Slots room = (Room.Slots) PortChecks.read(helper, PortChecks.at(helper, pos)).room();
        for (var item : List.of(Items.STONE, Items.ENDER_PEARL, Items.DIAMOND_PICKAXE)) {
            ItemStack stack = new ItemStack(item);
            helper.assertValueEqual(room.cap().limit(stack),
                (long) wrapper.getInventoryHandler().getStackLimit(0, stack),
                "downgraded empty slots retain each item's native limit");
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void controllerAndIoEnrollIndependentMembersAtomically(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos first = pos.east();
        BlockPos second = first.east();
        BlockPos late = second.east();
        BlockPos access = pos.south();
        ControllerBlockEntity controller = (ControllerBlockEntity) PortChecks.place(helper, pos,
            "sophisticatedstorage:controller");
        PortChecks.place(helper, first, "sophisticatedstorage:diamond_chest");
        StorageBlockEntity storage = (StorageBlockEntity) PortChecks.place(helper, second,
            "sophisticatedstorage:diamond_chest");
        StorageIOBlockEntity io = (StorageIOBlockEntity) PortChecks.place(helper, access,
            "sophisticatedstorage:storage_io");
        controller.addStorage(helper.absolutePos(first));
        controller.addStorage(helper.absolutePos(second));
        io.setControllerPos(helper.absolutePos(pos));
        helper.assertTrue(PortChecks.at(helper, access).proxyMembers()
            .equals(PortChecks.at(helper, pos).proxyMembers()), "IO represents controller members");
        ProxyChecks.lifecycle(helper, access, first, second, late,
            unsafe -> storage.getStorageWrapper().getUpgradeHandler().setStackInSlot(0, unsafe
                ? PortChecks.item("sophisticatedstorage:void_upgrade") : ItemStack.EMPTY), () -> {
                PortChecks.place(helper, late, "sophisticatedstorage:chest");
                controller.addStorage(helper.absolutePos(late));
            });
        helper.succeed();
    }
}
