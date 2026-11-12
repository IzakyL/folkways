package io.github.izakyl.folkways.core.shell.storage.functional;

import com.buuz135.functionalstorage.block.tile.DrawerTile;
import com.buuz135.functionalstorage.block.tile.StorageControllerTile;
import com.buuz135.functionalstorage.item.LinkingToolItem;
import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import io.github.izakyl.folkways.core.shell.storage.ProxyChecks;
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

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class FunctionalGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(FunctionalGameTests.class);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void atomicDrawerBatches(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        PortChecks.place(helper, pos, "functionalstorage:oak_2");
        PortChecks.roundTrip(helper, pos, true);
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void upgradesLocksAndCompacting(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        DrawerTile drawer = (DrawerTile) PortChecks.place(helper, pos, "functionalstorage:oak_2");
        for (String upgrade : List.of("void_upgrade", "obsidian_upgrade")) {
            drawer.getUtilityUpgrades().setStackInSlot(0, PortChecks.item("functionalstorage:" + upgrade));
            PortChecks.unusable(helper, pos);
            drawer.getUtilityUpgrades().setStackInSlot(0, ItemStack.EMPTY);
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "removing " + upgrade + " recovers");
        }
        for (String upgrade : List.of("collector_upgrade", "puller_upgrade", "pusher_upgrade")) {
            drawer.getUtilityUpgrades().setStackInSlot(0, PortChecks.item("functionalstorage:" + upgrade));
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "IO stays usable: " + upgrade);
            drawer.getUtilityUpgrades().setStackInSlot(0, ItemStack.EMPTY);
        }
        drawer.getStorageUpgrades().setStackInSlot(0, PortChecks.item("functionalstorage:creative_vending_upgrade"));
        PortChecks.unusable(helper, pos);
        drawer.getStorageUpgrades().setStackInSlot(0, ItemStack.EMPTY);
        PortChecks.at(helper, pos).put(helper.getLevel(), List.of(new ItemStack(Items.STONE)), List.of(), false, false);
        drawer.setLocked(true);
        PortChecks.at(helper, pos).take(helper.getLevel(), List.of(new Pull.Exact(new ItemStack(Items.STONE))),
            List.of(), false);
        Room.Slots room = (Room.Slots) PortChecks.read(helper, PortChecks.at(helper, pos)).room();
        helper.assertTrue(room.bound().size() == 1 && room.open() == 0, "empty locked cells cannot change goods");
        for (String shape : List.of("compacting_drawer", "simple_compacting_drawer", "compacting_framed_drawer")) {
            BlockPos comp = new BlockPos(2, 2, 1);
            PortChecks.place(helper, comp, "functionalstorage:" + shape);
            PortChecks.unusable(helper, comp);
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void controllerAndExtensionEnrollIndependentMembersAtomically(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos first = pos.east();
        BlockPos second = first.east();
        BlockPos late = second.east();
        BlockPos extension = pos.south();
        StorageControllerTile<?> controller = (StorageControllerTile<?>) PortChecks.place(helper, pos,
            "functionalstorage:storage_controller");
        PortChecks.place(helper, first, "functionalstorage:oak_2");
        DrawerTile drawer = (DrawerTile) PortChecks.place(helper, second, "functionalstorage:oak_2");
        PortChecks.place(helper, extension, "functionalstorage:controller_extension");
        controller.addConnectedDrawers(LinkingToolItem.ActionMode.ADD, helper.absolutePos(first),
            helper.absolutePos(second), helper.absolutePos(extension));
        helper.assertTrue(PortChecks.at(helper, extension).proxyMembers()
            .equals(PortChecks.at(helper, pos).proxyMembers()), "extension represents the same members");
        ProxyChecks.lifecycle(helper, extension, first, second, late,
            unsafe -> drawer.getUtilityUpgrades().setStackInSlot(0, unsafe
                ? PortChecks.item("functionalstorage:void_upgrade") : ItemStack.EMPTY), () -> {
                PortChecks.place(helper, late, "functionalstorage:oak_2");
                controller.addConnectedDrawers(LinkingToolItem.ActionMode.ADD, helper.absolutePos(late));
            });
        helper.succeed();
    }
}
