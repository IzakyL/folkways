package io.github.izakyl.folkways.cookery.tavern;

import io.github.izakyl.folkways.plugins.person.living.DiningDrinks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Real, component-preserving bottle stock belongs to the counter and follows its chunk lifecycle. */
public final class TavernCounterBlockEntity extends BlockEntity implements Container {
    private final NonNullList<ItemStack> items = NonNullList.withSize(2, ItemStack.EMPTY);
    public TavernCounterBlockEntity(BlockPos pos, BlockState state) { super(TavernCounters.COUNTER.get(), pos, state); }
    public int getContainerSize() { return items.size(); }
    public int getMaxStackSize() { return 1; }
    public boolean isEmpty() { return items.stream().allMatch(ItemStack::isEmpty); }
    public ItemStack getItem(int slot) { return items.get(slot); }
    public boolean canPlaceItem(int slot, ItemStack stack) { return slot >= 0 && slot < items.size() && DiningDrinks.accepts(stack); }
    public void setItem(int slot, ItemStack stack) {
        if (!stack.isEmpty() && (stack.getCount() != 1 || !canPlaceItem(slot, stack))) throw new IllegalArgumentException("bar counter accepts one registered drink per slot");
        items.set(slot, stack); setChanged();
    }
    public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) setChanged();
        return removed;
    }
    public ItemStack removeItemNoUpdate(int slot) { return ContainerHelper.takeItem(items, slot); }
    public void clearContent() { items.clear(); setChanged(); }
    public boolean stillValid(Player player) { return Container.stillValidBlockEntity(this, player); }
    @Override public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries); ContainerHelper.saveAllItems(tag, items, registries);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries); items.clear(); ContainerHelper.loadAllItems(tag, items, registries);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) { return saveWithoutMetadata(registries); }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
}
