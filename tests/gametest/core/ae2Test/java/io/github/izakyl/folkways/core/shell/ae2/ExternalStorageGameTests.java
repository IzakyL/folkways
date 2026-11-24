package io.github.izakyl.folkways.core.shell.ae2;

import appeng.api.config.AccessRestriction;
import appeng.api.config.Settings;
import appeng.api.parts.PartHelper;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEParts;
import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import io.github.izakyl.folkways.core.inventory.internal.InventoryObservations;
import io.github.izakyl.folkways.core.testing.ExternalStorageChecks;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_core", bus = EventBusSubscriber.Bus.MOD)
@PrefixGameTestTemplate(false)
public final class ExternalStorageGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        ExternalStorageChecks.registerProxyPort();
        event.register(ExternalStorageGameTests.class);
    }

    @SubscribeEvent
    public static void capabilities(RegisterCapabilitiesEvent event) {
        ExternalStorageChecks.registerProxyHandler(event);
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void attachedChestBelongsToTheNetwork(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        var level = helper.getLevel();
        BlockPos chest = NetworkRig.TERMINAL.relative(Direction.SOUTH);
        var drive = (DriveBlockEntity) level.getBlockEntity(helper.absolutePos(NetworkRig.DRIVE));
        drive.getInternalInventory().setItemDirect(0, ItemStack.EMPTY);
        var checks = new ExternalStorageChecks(helper, chest, NetworkRig.TERMINAL);
        helper.runAfterDelay(40, () -> {
            var bus = PartHelper.setPart(level, helper.absolutePos(NetworkRig.TERMINAL), Direction.SOUTH, null,
                AEParts.STORAGE_BUS.get());
            helper.runAfterDelay(100, () -> {
                checks.attached(rig.port().room());
                helper.assertTrue(rig.port().room().newKinds() == 1, "chest items do not inflate the type budget");
                bus.getConfigManager().putSetting(Settings.ACCESS, AccessRestriction.READ);
                helper.runAfterDelay(40, () -> {
                    helper.assertTrue(rig.port().room().items() == 0, "extract-only bus promises no room");
                    bus.getHost().removePartFromSide(Direction.SOUTH);
                    helper.runAfterDelay(40, () -> {
                        checks.detached();
                        helper.succeed();
                    });
                });
            });
        });
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void attachedProxyOwnsItsMembers(GameTestHelper helper) {
        new NetworkRig(helper);
        ExternalStorageChecks.proxy(helper, NetworkRig.TERMINAL.south(), NetworkRig.TERMINAL,
            () -> bus(helper, NetworkRig.TERMINAL, Direction.SOUTH));
    }

    @GameTest(template = "ae2_empty", templateNamespace = "folkways_core", timeoutTicks = 500)
    public static void interfaceLinksOwnAndMergeNetworks(GameTestHelper helper) {
        NetworkRig a = new NetworkRig(helper);
        NetworkRig b = new NetworkRig(helper, 2);
        var level = helper.getLevel();
        var host = PartHelper.getPartHost(level, helper.absolutePos(b.at(NetworkRig.TERMINAL)));
        host.removePartFromSide(Direction.NORTH);
        PartHelper.setPart(level, helper.absolutePos(b.at(NetworkRig.TERMINAL)), Direction.UP, null,
            AEParts.CRAFTING_TERMINAL.get());
        var outer = WorldPos.of(level, helper.absolutePos(NetworkRig.TERMINAL));
        var inner = WorldPos.of(level, helper.absolutePos(b.at(NetworkRig.TERMINAL)));
        var colony = ExternalStorageChecks.enroll(helper, List.of(outer, inner));
        BlockPos reverseHost = b.at(NetworkRig.OTHER.east());
        for (BlockPos cable : List.of(reverseHost, NetworkRig.OTHER.east())) {
            PartHelper.setPart(level, helper.absolutePos(cable), null, null,
                AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        }
        helper.runAfterDelay(80, () -> {
            a.stock(Items.WHEAT, 5);
            b.stock(Items.WHEAT, 7);
            bus(helper, NetworkRig.TERMINAL, Direction.SOUTH);
            helper.setBlock(NetworkRig.TERMINAL.south(), AEBlocks.INTERFACE.block());
            helper.runAfterDelay(100, () -> {
                InventoryObservations.publish(colony, level);
                helper.assertTrue(a.port().node().getGrid() != b.port().node().getGrid(),
                    "the attachment separates the native grids");
                helper.assertTrue(!StoreAccess.candidate(level, inner.block(level)), "the inner terminal is owned");
                helper.assertTrue(colony.inventory().snapshot().store(inner).isEmpty(),
                    "the inner enrollment is suppressed");
                ExternalStorageChecks.assertStock(helper, colony, 12);
                var reverse = bus(helper, reverseHost, Direction.NORTH);
                PartHelper.setPart(level, helper.absolutePos(reverseHost.north()), null, null,
                    AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
                PartHelper.setPart(level, helper.absolutePos(reverseHost.north()), Direction.SOUTH, null,
                    AEParts.INTERFACE.get());
                helper.runAfterDelay(100, () -> {
                    InventoryObservations.publish(colony, level);
                    var snapshot = colony.inventory().snapshot();
                    helper.assertTrue(a.port().identity().key().equals(b.port().identity().key())
                        && snapshot.stores().size() == 1
                        && snapshot.store(outer).orElseThrow().aliases().contains(inner), "mutual links merge both terminals");
                    ExternalStorageChecks.assertStock(helper, colony, 12);
                    helper.setBlock(reverseHost.north(), net.minecraft.world.level.block.Blocks.AIR);
                    reverse.getHost().removePartFromSide(Direction.NORTH);
                    helper.runAfterDelay(100, () -> {
                        InventoryObservations.publish(colony, level);
                        helper.assertTrue(colony.inventory().snapshot().store(inner).isEmpty(),
                            "breaking the cycle suppresses the inner alias again");
                        ExternalStorageChecks.assertStock(helper, colony, 12);
                        helper.succeed();
                    });
                });
            });
        });
    }

    private static appeng.parts.storagebus.StorageBusPart bus(GameTestHelper helper, BlockPos host, Direction face) {
        return PartHelper.setPart(helper.getLevel(), helper.absolutePos(host), face, null, AEParts.STORAGE_BUS.get());
    }
}
