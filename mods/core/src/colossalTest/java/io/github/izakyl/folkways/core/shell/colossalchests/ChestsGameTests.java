package io.github.izakyl.folkways.core.shell.colossalchests;

import io.github.izakyl.folkways.core.inventory.api.port.Pull;
import io.github.izakyl.folkways.core.shell.storage.NetworkChecks;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_core")
@PrefixGameTestTemplate(false)
public final class ChestsGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(ChestsGameTests.class);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core", timeoutTicks = 250)
    public static void wholeChestThroughCoreWallAndInterface(GameTestHelper helper) {
        new ChestRig(helper);
        var key = PortChecks.at(helper, ChestRig.CORE).identity().key();
        for (BlockPos access : List.of(ChestRig.CORE, ChestRig.WALL, ChestRig.INTERFACE)) {
            helper.assertTrue(PortChecks.at(helper, access).identity().key().equals(key),
                "all aliases see one inventory");
            PortChecks.roundTrip(helper, access, false);
        }
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(ChestRig.WALL)) == null,
            "wall access works without a block entity");
        PortChecks.at(helper, ChestRig.INTERFACE).put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 23)),
            List.of(), false, false);
        NetworkChecks.observed(helper, List.of(ChestRig.CORE, ChestRig.WALL, ChestRig.INTERFACE), 1, 23);
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void incompleteStructuresRefuseAndUncolossalStores(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 2);
        for (String material : List.of("wood", "copper", "iron", "silver", "gold", "diamond", "obsidian",
                "netherite")) {
            for (String block : List.of("colossal_chest_", "chest_wall_", "interface_")) {
                PortChecks.place(helper, pos, "colossalchests:" + block + material);
                NetworkChecks.refused(helper, pos, "disconnected");
            }
        }
        PortChecks.place(helper, pos, "colossalchests:uncolossal_chest");
        // The native miniature chest has a single slot.
        var port = PortChecks.at(helper, pos);
        helper.assertTrue(port.usability().usable() && port.proxyMembers().isEmpty(), "uncolossal is ordinary storage");
        helper.assertTrue(port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 12)), List.of(), false, true)
            .done() && PortChecks.count(helper, port) == 0, "miniature insertion simulates without mutation");
        helper.assertTrue(port.put(helper.getLevel(), List.of(new ItemStack(Items.STONE, 12)), List.of(), false, false)
            .done(), "miniature accepts real goods");
        helper.assertTrue(port.take(helper.getLevel(), List.of(new Pull.Exact(
            new ItemStack(Items.STONE, 12))), List.of(), false).done() && PortChecks.count(helper, port) == 0,
            "miniature round trip");
        helper.succeed();
    }

    @GameTest(template = "storage_empty_large", templateNamespace = "folkways_core")
    public static void breakingChestDisconnectsEveryAlias(GameTestHelper helper) {
        new ChestRig(helper);
        var old = PortChecks.at(helper, ChestRig.WALL);
        helper.setBlock(ChestRig.CORE.offset(1, 1, 1), Blocks.AIR);
        NetworkChecks.refused(helper, ChestRig.CORE, "disconnected");
        NetworkChecks.refused(helper, ChestRig.WALL, "disconnected");
        NetworkChecks.refused(helper, ChestRig.INTERFACE, "disconnected");
        helper.assertTrue(!old.usability().usable(), "previous alias cannot mutate a disconnected chest");
        helper.succeed();
    }
}
