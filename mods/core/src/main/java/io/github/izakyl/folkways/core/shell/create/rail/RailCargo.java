package io.github.izakyl.folkways.core.shell.create.rail;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.transport.api.TrainControl;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.items.IItemHandler;

public record RailCargo(CarriageContraptionEntity entity) implements Port<ItemStack>, TrainControl {

    public static void install() {
        Ports.register(ResourceKind.ITEM, ResourceLocation.parse("folkways_core:rail/cargo"), Ports.SPECIFIC,
            (level, cell) -> !cell.in(level) ? List.of()
                : level.getEntitiesOfClass(CarriageContraptionEntity.class, new AABB(cell.block(level)),
                    entity -> WorldSpaces.at(entity).equals(cell) && hasStorage(entity.getCarriage())).stream()
                    .<Port<ItemStack>>map(RailCargo::new).toList());
    }

    /** Used by mail routing even when the carriage entity is outside loaded chunks. */
    public static boolean hasStorage(Carriage carriage) {
        IItemHandler items = storage(carriage);
        return items != null && items.getSlots() > 0;
    }

    private static IItemHandler storage(Carriage carriage) {
        return carriage == null ? null : carriage.storage.getAllItems();
    }

    private IItemHandler loadedStorage(ServerLevel level) {
        return entity.level() == level && level.getEntity(entity.getUUID()) == entity && entity.isAlive()
            ? storage(entity.getCarriage()) : null;
    }

    @Override
    public boolean belongsToTrain(UUID train) {
        var carriage = entity.getCarriage();
        return carriage != null && carriage.train != null && carriage.train.id.equals(train);
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(entity.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(entity);
    }

    @Override
    public String part() {
        return "cargo/" + entity.getUUID();
    }

    @Override
    public Object leaseKey() {
        return part();
    }

    @Override
    public boolean valid(ServerLevel level) {
        IItemHandler items = loadedStorage(level);
        return items != null && items.getSlots() > 0;
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        IItemHandler items = loadedStorage(level);
        if (items == null) {
            return List.of();
        }
        List<ItemStack> found = new ArrayList<>();
        for (int slot = 0; slot < items.getSlots(); slot++) {
            ItemStack stack = items.getStackInSlot(slot);
            if (!stack.isEmpty()) {
                found.add(stack.copy());
            }
        }
        return List.copyOf(found);
    }

    @Override
    public Cost cost() {
        return Cost.REMOTE;
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        IItemHandler items = loadedStorage(level);
        if (items == null) {
            return ItemStack.EMPTY;
        }
        ItemStack left = offered.copy();
        for (int slot = 0; slot < items.getSlots() && !left.isEmpty(); slot++) {
            left = items.insertItem(slot, left, simulate);
        }
        return offered.copyWithCount(offered.getCount() - left.getCount());
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        IItemHandler items = loadedStorage(level);
        if (items == null) {
            return ItemStack.EMPTY;
        }
        int left = wanted.getCount();
        for (int slot = 0; slot < items.getSlots() && left > 0; slot++) {
            if (ItemStack.isSameItemSameComponents(items.getStackInSlot(slot), wanted)) {
                left -= items.extractItem(slot, left, simulate).getCount();
            }
        }
        return wanted.copyWithCount(wanted.getCount() - left);
    }
}
