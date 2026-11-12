package io.github.izakyl.folkways.core.shell.storage.drawers;

import com.jaquadro.minecraft.storagedrawers.api.storage.IDrawerAttributesModifiable;
import com.jaquadro.minecraft.storagedrawers.api.storage.attribute.LockAttribute;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityController;
import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityDrawers;
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
public final class DrawersGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(DrawersGameTests.class);
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void ordinaryTransfersRespectAccessAndDirectedPutBypassesIt(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        PortChecks.place(helper, pos, "storagedrawers:oak_full_drawers_2");
        var port = PortChecks.at(helper, pos);
        var rules = List.<io.github.izakyl.folkways.core.inventory.api.StoreRule>of(
            new io.github.izakyl.folkways.core.inventory.api.StoreRule.Access(port.identity().home(), false, false));
        var goods = List.of(new ItemStack(Items.STONE, 2));
        var pull = List.<io.github.izakyl.folkways.core.inventory.api.port.Pull>of(
            new io.github.izakyl.folkways.core.inventory.api.port.Pull.Exact(new ItemStack(Items.STONE)));
        for (boolean simulate : List.of(true, false)) {
            helper.assertTrue(!port.put(helper.getLevel(), goods, rules, false, simulate).done(),
                "ordinary put observes the store access flag");
            helper.assertTrue(port.put(helper.getLevel(), goods, rules, true, simulate).done(),
                "directed put bypasses the store access flag");
        }
        for (boolean simulate : List.of(true, false)) {
            helper.assertTrue(!port.take(helper.getLevel(), pull, rules, simulate).done(),
                "take observes the take access flag");
        }
        helper.assertTrue(PortChecks.count(helper, port) == 2, "refused transfers leave the drawer unchanged");
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void atomicDrawerBatches(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        PortChecks.place(helper, pos, "storagedrawers:oak_full_drawers_2");
        PortChecks.roundTrip(helper, pos, true);
        helper.succeed();
    }

    @GameTest(template = "storage_empty", templateNamespace = "folkways_core")
    public static void upgradesLocksAndCompacting(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockEntityDrawers drawer = (BlockEntityDrawers) PortChecks.place(helper, pos,
            "storagedrawers:oak_full_drawers_2");
        for (String upgrade : List.of("void_upgrade", "conversion_upgrade", "creative_vending_upgrade")) {
            drawer.upgrades().setUpgrade(0, PortChecks.item("storagedrawers:" + upgrade));
            PortChecks.unusable(helper, pos);
            drawer.upgrades().setUpgrade(0, ItemStack.EMPTY);
            helper.assertTrue(PortChecks.at(helper, pos).usability().usable(), "removing " + upgrade + " recovers");
        }
        drawer.getDrawer(0).setStoredItem(new ItemStack(Items.STONE), 0);
        ((IDrawerAttributesModifiable) drawer.getDrawerAttributes()).setItemLocked(LockAttribute.LOCK_POPULATED, true);
        drawer.getDrawer(0).setStoredItem(new ItemStack(Items.STONE), 1);
        drawer.getDrawer(0).setStoredItemCount(0);
        Room.Slots room = (Room.Slots) PortChecks.read(helper, PortChecks.at(helper, pos)).room();
        helper.assertTrue(room.bound().size() == 1 && room.open() == 1, "empty locked binding remains keyed");
        helper.assertTrue(room.bound().getFirst().room() > 0, "locked drawer has room for its prototype");
        for (String shape : List.of("compacting_drawers_2", "compacting_drawers_3")) {
            BlockPos comp = new BlockPos(2, 2, 1);
            PortChecks.place(helper, comp, "storagedrawers:" + shape);
            PortChecks.unusable(helper, comp);
        }
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void controllerEnrollsIndependentMembersAtomically(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 2, 1);
        BlockPos first = pos.east();
        BlockPos second = first.east();
        BlockPos late = second.east();
        BlockEntityController controller = (BlockEntityController) PortChecks.place(helper, pos,
            "storagedrawers:controller");
        PortChecks.place(helper, first, "storagedrawers:oak_full_drawers_2");
        BlockEntityDrawers drawer = (BlockEntityDrawers) PortChecks.place(helper, second,
            "storagedrawers:oak_full_drawers_2");
        controller.updateCache();
        ProxyChecks.lifecycle(helper, pos, first, second, late,
            unsafe -> drawer.upgrades().setUpgrade(0, unsafe
                ? PortChecks.item("storagedrawers:void_upgrade") : ItemStack.EMPTY), () -> {
                PortChecks.place(helper, late, "storagedrawers:oak_full_drawers_2");
                controller.updateCache();
            });
        helper.succeed();
    }
}
