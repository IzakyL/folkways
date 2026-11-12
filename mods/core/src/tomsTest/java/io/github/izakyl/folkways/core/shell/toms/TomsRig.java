package io.github.izakyl.folkways.core.shell.toms;

import com.tom.storagemod.block.AbstractStorageTerminalBlock;
import com.tom.storagemod.block.InventoryCableConnectorBlock;
import io.github.izakyl.folkways.core.shell.storage.PortChecks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;

public final class TomsRig {

    public static final BlockPos CONNECTOR = new BlockPos(5, 1, 2);
    public static final BlockPos MEMBER = new BlockPos(6, 1, 2);
    public static final BlockPos SECOND = new BlockPos(7, 1, 2);
    public static final BlockPos TERMINAL = new BlockPos(5, 1, 3);
    public static final BlockPos CRAFTING = new BlockPos(5, 1, 1);
    public static final BlockPos CABLE = new BlockPos(4, 1, 2);
    public static final BlockPos FRAMED = new BlockPos(5, 2, 2);

    public TomsRig(GameTestHelper helper) {
        helper.setBlock(MEMBER, Blocks.BARREL);
        helper.setBlock(SECOND, Blocks.BARREL);
        PortChecks.place(helper, CONNECTOR, "toms_storage:inventory_connector");
        helper.setBlock(TERMINAL, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("toms_storage:storage_terminal"))
            .defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING, Direction.NORTH));
        helper.setBlock(CRAFTING, BuiltInRegistries.BLOCK.get(ResourceLocation.parse("toms_storage:crafting_terminal"))
            .defaultBlockState().setValue(AbstractStorageTerminalBlock.FACING, Direction.SOUTH));
        helper.setBlock(CABLE, BuiltInRegistries.BLOCK.get(
            ResourceLocation.parse("toms_storage:inventory_cable_connector"))
            .defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST));
        helper.setBlock(FRAMED, BuiltInRegistries.BLOCK.get(
            ResourceLocation.parse("toms_storage:inventory_cable_connector_framed"))
            .defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.DOWN));
    }
}
