package io.github.izakyl.folkways.plugins.cookery.racks;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.KitchenwareRacksBlockEntity;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.Tags;

// This is a view of the rack's two real slots, never a second inventory holding copies of its tools.
public final class RackContainer implements Container {

    private final KitchenwareRacksBlockEntity rack;

    public RackContainer(KitchenwareRacksBlockEntity rack) {
        this.rack = rack;
    }

    @Override
    public int getContainerSize() {
        return 2;
    }

    @Override
    public boolean isEmpty() {
        return getItem(0).isEmpty() && getItem(1).isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        checkSlot(slot);
        return live() ? (slot == 0 ? rack.getItemLeft() : rack.getItemRight()) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack stack = getItem(slot);
        if (stack.isEmpty() || count <= 0) {
            return ItemStack.EMPTY;
        }
        ItemStack taken = stack.split(count);
        if (stack.isEmpty()) {
            write(slot, ItemStack.EMPTY);
        }
        setChanged();
        return taken;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack taken = getItem(slot);
        if (!taken.isEmpty()) {
            write(slot, ItemStack.EMPTY);
            setChanged();
        }
        return taken;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        checkSlot(slot);
        if (!live()) {
            throw new IllegalStateException("the kitchenware rack is no longer in the world");
        }
        if (!stack.isEmpty() && (stack.getCount() > 1 || !canPlaceItem(slot, stack))) {
            throw new IllegalArgumentException("a kitchenware rack slot holds one tool");
        }
        write(slot, stack);
        setChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        checkSlot(slot);
        // Occupancy is checked by the mover, so a full rack still declares the same accepted item types.
        return live() && !stack.isEmpty() && stack.is(Tags.Items.TOOLS);
    }

    @Override
    public void setChanged() {
        if (live()) {
            rack.refresh();
        }
    }

    @Override
    public boolean stillValid(Player player) {
        Level level = rack.getLevel();
        return live() && player.level() == level
            && WorldSpaces.world(level, WorldPos.of(level, rack.getBlockPos()))
                .filter(at -> player.distanceToSqr(at.add(0, 0.5, 0)) <= 64).isPresent();
    }

    @Override
    public void clearContent() {
        if (live()) {
            write(0, ItemStack.EMPTY);
            write(1, ItemStack.EMPTY);
            setChanged();
        }
    }

    private boolean live() {
        Level level = rack.getLevel();
        return level != null && !rack.isRemoved() && level.isLoaded(rack.getBlockPos())
            && level.getBlockEntity(rack.getBlockPos()) == rack;
    }

    private void write(int slot, ItemStack stack) {
        if (slot == 0) {
            rack.setItemLeft(stack);
        } else {
            rack.setItemRight(stack);
        }
    }

    private static void checkSlot(int slot) {
        if (slot < 0 || slot >= 2) {
            throw new IndexOutOfBoundsException("rack slot " + slot);
        }
    }
}
