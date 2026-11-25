package io.github.izakyl.folkways.core.shell.ae2;

import io.github.izakyl.folkways.core.inventory.api.port.StoreAccess;
import appeng.api.config.Actionable;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;

final class NetworkRig {

    static final BlockPos POWER = new BlockPos(2, 2, 2);
    static final BlockPos DRIVE = new BlockPos(3, 2, 2);
    static final BlockPos TERMINAL = new BlockPos(4, 2, 2);
    static final BlockPos OTHER = new BlockPos(5, 2, 2);
    private final GameTestHelper helper;
    private final int offset;

    NetworkRig(GameTestHelper helper) {
        this(helper, 0);
    }

    NetworkRig(GameTestHelper helper, int offset) {
        this.helper = helper;
        this.offset = offset;
        helper.setBlock(at(POWER), AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(at(DRIVE), AEBlocks.DRIVE.block());
        var drive = (DriveBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(at(DRIVE)));
        drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_1K.stack());
        terminal(at(TERMINAL));
        terminal(at(OTHER));
    }

    private void terminal(BlockPos pos) {
        PartHelper.setPart(helper.getLevel(), helper.absolutePos(pos), null, null,
            AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT));
        PartHelper.setPart(helper.getLevel(), helper.absolutePos(pos), Direction.NORTH, null,
            AEParts.CRAFTING_TERMINAL.get());
    }

    BlockPos at(BlockPos pos) {
        return pos.south(offset);
    }

    NetworkPort port() {
        NetworkPort port = Ae2Devices.at(helper.getLevel(), helper.absolutePos(at(TERMINAL))).orElseThrow();
        helper.assertTrue(StoreAccess.usability(helper.getLevel(), port).usable(), "minimal ME network is powered and has a channel");
        return port;
    }

    void stock(Item item, long count) {
        NetworkPort port = port();
        long inserted = port.storage().insert(AEItemKey.of(item), count, Actionable.MODULATE,
            Ae2Devices.source(port.node()));
        helper.assertTrue(inserted == count, "fixture storage accepted its stock");
    }

    long count(Item item) {
        return port().storage().getAvailableStacks().get(AEItemKey.of(item));
    }
}
