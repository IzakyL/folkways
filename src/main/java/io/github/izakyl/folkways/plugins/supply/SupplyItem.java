package io.github.izakyl.folkways.plugins.supply;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

final class SupplyItem extends BlockItem {
    SupplyItem(Block block, Item.Properties properties) { super(block, properties); }
    @Override
    protected boolean canPlace(BlockPlaceContext context, BlockState state) {
        return context.getPlayer() != null && context.getPlayer().isCreative() && super.canPlace(context, state);
    }
}
