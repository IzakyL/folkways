package io.github.izakyl.folkways.cookery.tavern.mixin;

import com.github.ysbbbbbb.kaleidoscopetavern.block.deco.BarCounterBlock;
import io.github.izakyl.folkways.cookery.tavern.TavernCounterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BarCounterBlock.class)
public abstract class BarCounterBlockMixin extends HorizontalDirectionalBlock implements EntityBlock {
    protected BarCounterBlockMixin(Properties properties) { super(properties); }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new TavernCounterBlockEntity(pos, state); }
    @Override protected net.minecraft.world.ItemInteractionResult useItemOn(net.minecraft.world.item.ItemStack stack, BlockState state,
            Level level, BlockPos pos, net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
            net.minecraft.world.phys.BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof TavernCounterBlockEntity counter && counter.canPlaceItem(0, stack)) {
            for (int slot = 0; slot < counter.getContainerSize(); slot++) if (counter.getItem(slot).isEmpty()) {
                if (!level.isClientSide) {
                    counter.setItem(slot, stack.copyWithCount(1));
                    stack.consume(1, player);
                }
                return net.minecraft.world.ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
            return net.minecraft.world.ItemInteractionResult.CONSUME;
        }
        return net.minecraft.world.ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }
    @Override protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.phys.BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof TavernCounterBlockEntity counter) {
            for (int slot = counter.getContainerSize() - 1; slot >= 0; slot--) if (!counter.getItem(slot).isEmpty()) {
                if (!level.isClientSide) {
                    var bottle = counter.removeItem(slot, 1);
                    if (!player.getInventory().add(bottle)) player.drop(bottle, false);
                }
                return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return net.minecraft.world.InteractionResult.PASS;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving) {
        if (!state.is(next.getBlock())) {
            if (!level.isClientSide && level.getBlockEntity(pos) instanceof TavernCounterBlockEntity counter) {
                Containers.dropContents(level, pos, counter);
                level.updateNeighbourForOutputSignal(pos, this);
            }
            super.onRemove(state, level, pos, next, moving);
        }
    }
}
