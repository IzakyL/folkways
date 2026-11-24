package io.github.izakyl.folkways.core.shell.rs2;

import com.refinedmods.refinedstorage.api.network.impl.node.externalstorage.ExternalStorageNetworkNode;
import com.refinedmods.refinedstorage.api.storage.AccessMode;
import com.refinedmods.refinedstorage.common.content.Blocks;
import com.refinedmods.refinedstorage.common.storage.AbstractDiskContainerBlockEntity;
import com.refinedmods.refinedstorage.common.storage.externalstorage.AbstractExternalStorageBlockEntity;
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

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core")
    public static void attachmentChangesOwnershipImmediately(GameTestHelper helper) {
        BlockPos attachment = new BlockPos(1, 1, 1);
        BlockPos chest = attachment.south();
        helper.setBlock(chest, net.minecraft.world.level.block.Blocks.CHEST);
        var level = helper.getLevel();
        BlockPos target = helper.absolutePos(chest);
        helper.assertFalse(StoreAccess.attached(level, target), "the target starts independent");

        external(helper, attachment, Direction.SOUTH);
        helper.assertTrue(StoreAccess.attached(level, target), "placing the attachment immediately claims the target");

        helper.setBlock(attachment, net.minecraft.world.level.block.Blocks.AIR);
        helper.assertFalse(StoreAccess.attached(level, target), "removing the attachment immediately frees the target");
        helper.succeed();
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 300)
    public static void attachedChestBelongsToTheNetwork(GameTestHelper helper) {
        NetworkRig rig = new NetworkRig(helper);
        var level = helper.getLevel();
        BlockPos attachment = NetworkRig.CABLE.relative(Direction.SOUTH);
        BlockPos chest = attachment.relative(Direction.SOUTH);
        var drive = (AbstractDiskContainerBlockEntity<?>) level.getBlockEntity(helper.absolutePos(NetworkRig.DRIVE));
        drive.getDiskInventory().setItem(0, ItemStack.EMPTY);
        drive.setChanged();
        var checks = new ExternalStorageChecks(helper, chest, NetworkRig.TERMINAL);
        var terminal = WorldPos.of(level, helper.absolutePos(NetworkRig.TERMINAL));
        var presence = checks.colony().service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
        // RS2's topology scan assumes every member is in-world. Keep the colony crafting provider off this rig.
        presence.settings.set(terminal, presence.settings.at(terminal).toggled("commission"));
        helper.runAfterDelay(40, () -> {
            helper.setBlock(attachment, Blocks.INSTANCE.getExternalStorage().getDefault().rotated(Direction.SOUTH));
            helper.runAfterDelay(100, () -> {
                checks.attached(rig.port().room());
                var bus = (AbstractExternalStorageBlockEntity) level.getBlockEntity(helper.absolutePos(attachment));
                var node = (ExternalStorageNetworkNode) bus.getContainerProvider().getContainers()
                    .iterator().next().getNode();
                node.getStorageConfiguration().setAccessMode(AccessMode.EXTRACT);
                helper.assertTrue(rig.port().room().items() == 0, "extract-only external storage promises no room");
                helper.setBlock(attachment, net.minecraft.world.level.block.Blocks.AIR);
                helper.runAfterDelay(40, () -> {
                    checks.detached();
                    helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void attachedProxyOwnsItsMembers(GameTestHelper helper) {
        new NetworkRig(helper);
        BlockPos attachment = NetworkRig.CABLE.south();
        var colony = ExternalStorageChecks.proxy(helper, attachment.south(), NetworkRig.TERMINAL,
            () -> external(helper, attachment, Direction.SOUTH));
        var terminal = WorldPos.of(helper.getLevel(), helper.absolutePos(NetworkRig.TERMINAL));
        var presence = colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
        presence.settings.set(terminal, presence.settings.at(terminal).toggled("commission"));
    }

    @GameTest(template = "rs2_empty", templateNamespace = "folkways_core", timeoutTicks = 400)
    public static void interfacesDoNotExposeTheInnerDisk(GameTestHelper helper) {
        NetworkRig a = new NetworkRig(helper);
        NetworkRig b = new NetworkRig(helper, 1024L, 3);
        var level = helper.getLevel();
        var outer = WorldPos.of(level, helper.absolutePos(NetworkRig.TERMINAL));
        var inner = WorldPos.of(level, helper.absolutePos(b.at(NetworkRig.TERMINAL)));
        var colony = ExternalStorageChecks.enroll(helper, List.of(outer, inner));
        var presence = colony.service(Rs2Integration.OWNER, NetworkPresence.class).orElseThrow();
        for (var terminal : List.of(outer, inner)) {
            presence.settings.set(terminal, presence.settings.at(terminal).toggled("commission"));
        }
        BlockPos attachment = NetworkRig.CABLE.south();
        helper.setBlock(attachment.south(), Blocks.INSTANCE.getInterface());
        helper.runAfterDelay(80, () -> {
            b.stock(Items.WHEAT, 7);
            external(helper, attachment, Direction.SOUTH);
            helper.runAfterDelay(100, () -> {
                InventoryObservations.publish(colony, level);
                helper.assertTrue(a.count(Items.WHEAT) == 0 && StoreAccess.candidate(level, inner.block(level)),
                    "an unconfigured RS2 interface exposes none of the inner disk's contents");
                ExternalStorageChecks.assertStock(helper, colony, 7);
                helper.setBlock(NetworkRig.OTHER.south(), Blocks.INSTANCE.getInterface());
                external(helper, b.at(NetworkRig.OTHER).north(), Direction.NORTH);
                helper.runAfterDelay(100, () -> {
                    InventoryObservations.publish(colony, level);
                    helper.assertTrue(colony.inventory().snapshot().stores().size() == 2,
                        "two buffer connections still leave two independently accessible disks");
                    ExternalStorageChecks.assertStock(helper, colony, 7);
                    helper.succeed();
                });
            });
        });
    }

    private static void external(GameTestHelper helper, BlockPos attachment, Direction side) {
        helper.setBlock(attachment, Blocks.INSTANCE.getExternalStorage().getDefault().rotated(side));
    }
}
