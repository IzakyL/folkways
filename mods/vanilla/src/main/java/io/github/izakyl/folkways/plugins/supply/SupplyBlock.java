package io.github.izakyl.folkways.plugins.supply;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class SupplyBlock extends Block implements EntityBlock {
    public SupplyBlock(Properties properties) { super(properties); }
    public BlockEntity newBlockEntity(BlockPos at, BlockState state) { return new SupplyBox(at, state); }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos at, Player player,
                                               BlockHitResult hit) {
        if (!player.isCreative()) return InteractionResult.PASS;
        if (level.isClientSide() && level.getBlockEntity(at) instanceof SupplyBox box) SupplyPanel.open(box);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
