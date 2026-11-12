package io.github.izakyl.folkways.core.shell.fxntstorage;

import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.shell.storage.NetworkChecks;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import java.util.List;
import java.util.Set;
import net.fxnt.fxntstorage.backpack.BackpackEntity;
import net.fxnt.fxntstorage.backpack.inventory.BackpackSlotLayout;
import net.fxnt.fxntstorage.container.StorageBoxEntity;
import net.fxnt.fxntstorage.simple_storage.SimpleStorageBoxEntity;
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

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class StorageGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StorageGameTests.class);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void boxesBackpacksAndNativeLimits(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 2);
        for (String block : List.of("storage_box", "cardboard_storage_box", "reserve_storage_box", "backpack")) {
            PortChecks.place(helper, pos, "fxntstorage:" + block);
            PortChecks.roundTrip(helper, pos, false);
        }
        var backpack = (BackpackEntity)
            PortChecks.place(helper, pos, "fxntstorage:hardened_backpack");
        var layout = BackpackSlotLayout.createLayout();
        backpack.getItemHandler().setStackInSlot(layout.tools().getStartIndex(), new ItemStack(Items.DIAMOND_AXE));
        var port = PortChecks.at(helper, pos);
        var room = (Room.Slots)
            PortChecks.read(helper, port).room();
        helper.assertTrue(room.open() == layout.items().getCount()
            && room.cap().limit(new ItemStack(Items.DIAMOND_AXE)) == backpack.getStackMultiplier(),
            "main slots only; unstackables obey native backpack multiplier");
        helper.assertTrue(PortChecks.count(helper, port) == 0, "tool and upgrade sections are not colony stock");
        PortChecks.place(helper, pos, "fxntstorage:oak_simple_storage_box");
        PortChecks.roundTrip(helper, pos, true);
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void unsafeUpgradesAndAutomationKeepReasons(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 2);
        var box = (StorageBoxEntity)
            PortChecks.place(helper, pos, "fxntstorage:storage_box");
        box.setVoidUpgrade(true);
        NetworkChecks.refused(helper, pos, "void");
        box.setVoidUpgrade(false);
        helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "removing void recovers");
        var simple = (SimpleStorageBoxEntity)
            PortChecks.place(helper, pos, "fxntstorage:oak_simple_storage_box");
        simple.getItemHandler().setStackInSlot(1, PortChecks.item("fxntstorage:storage_box_void_upgrade"));
        NetworkChecks.refused(helper, pos, "void");
        simple.getItemHandler().setStackInSlot(1, ItemStack.EMPTY);
        simple.getItemHandler().setStackInSlot(2, PortChecks.item("fxntstorage:storage_box_compacting_upgrade"));
        NetworkChecks.refused(helper, pos, "compacting");
        var backpack = (BackpackEntity)
            PortChecks.place(helper, pos, "fxntstorage:backpack");
        int upgradeSlot = BackpackSlotLayout.createLayout()
            .upgrades().getStartIndex();
        for (String upgrade : List.of("backpack_void_upgrade", "backpack_magnet_upgrade", "backpack_workshop_upgrade",
                "backpack_itempickup_upgrade", "backpack_refill_upgrade")) {
            backpack.getItemHandler().setStackInSlot(upgradeSlot, PortChecks.item("fxntstorage:" + upgrade));
            NetworkChecks.refused(helper, pos, "upgrade");
        }
        backpack.getItemHandler().setStackInSlot(upgradeSlot, ItemStack.EMPTY);
        helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "removing unsafe upgrade recovers");
        for (String block : List.of("passer_block", "smart_passer_block", "storage_interface",
                "storage_interface_filtered")) {
            PortChecks.place(helper, pos, "fxntstorage:" + block);
            NetworkChecks.refused(helper, pos, "buffer");
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 200)
    public static void controllerIsPutOnlyProxyAndStockCountsOnce(GameTestHelper helper) {
        StorageRig rig = new StorageRig(helper);
        var port = PortChecks.at(helper, StorageRig.CONTROLLER);
        helper.assertTrue(port.proxyMembers().orElseThrow().size() == 1
            && port.operations().equals(Set.of(StorePort.Operation.PUT)),
            "controller represents independent containers and only grants native hand insertion");
        helper.assertTrue(PortChecks.at(helper, StorageRig.MEMBER).put(helper.getLevel(),
            List.of(new ItemStack(Items.STONE, 23)), List.of(), false, false).done(), "seed member");
        NetworkChecks.observed(helper, List.of(StorageRig.CONTROLLER, StorageRig.MEMBER), 1, 23);
    }
}
