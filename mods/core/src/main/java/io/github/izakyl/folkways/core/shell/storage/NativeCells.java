package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.inventory.api.port.PortIdentity;
import io.github.izakyl.folkways.core.inventory.api.port.StorePort;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.stream.IntStream;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/** Cell adapters for explicitly audited native inventories, never a generic capability opt-in. */
public final class NativeCells {

    private NativeCells() {
    }

    public static StorePort refused(WorldPos home, String reason) {
        return new CellPort(PortIdentity.block(home), List.of(), () -> Verdicts.because(reason), false);
    }

    public static List<CellPort.Cell> container(Container inventory) {
        return IntStream.range(0, inventory.getContainerSize()).mapToObj(slot -> (CellPort.Cell) new CellPort.Cell() {
            @Override
            public ItemStack prototype() {
                return inventory.getItem(slot).copyWithCount(1);
            }

            @Override
            public int count() {
                return inventory.getItem(slot).getCount();
            }

            @Override
            public int limit(ItemStack stack) {
                return Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize());
            }

            @Override
            public boolean accepts(ItemStack stack) {
                return inventory.canPlaceItem(slot, stack);
            }

            @Override
            public void write(ItemStack prototype, int count) {
                inventory.setItem(slot, prototype.copyWithCount(count));
                inventory.setChanged();
            }
        }).toList();
    }

    public static CellPort.Cell handler(IItemHandlerModifiable handler, int slot, int multiplier) {
        return new CellPort.Cell() {
            @Override
            public ItemStack prototype() {
                return handler.getStackInSlot(slot).copyWithCount(1);
            }

            @Override
            public int count() {
                return handler.getStackInSlot(slot).getCount();
            }

            @Override
            public int limit(ItemStack stack) {
                return Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize() * multiplier);
            }

            @Override
            public boolean accepts(ItemStack stack) {
                return handler.isItemValid(slot, stack);
            }

            @Override
            public void write(ItemStack prototype, int count) {
                handler.setStackInSlot(slot, prototype.copyWithCount(count));
            }
        };
    }
}
