package io.github.izakyl.folkways.core.shell.warfare;

import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

/**
 * A Superb Warfare vehicle's own container as an item port: what its guns' native reload feeds from, and where its
 * supplies are hauled to. A mortar uses its tube as a {@link WarfareBreech breech} port.
 */
public record WarfareAmmo(VehicleEntity vehicle) implements Port<ItemStack> {

    public static final String AMMO = "ammo";

    /** The vehicles' ammunition ports in this cell. */
    static List<Port<ItemStack>> at(ServerLevel level, WorldPos cell) {
        if (!cell.in(level)) {
            return List.of();
        }
        return level.getEntitiesOfClass(VehicleEntity.class, new AABB(cell.block(level)).inflate(2),
                vehicle -> !(vehicle instanceof MortarEntity) && vehicle.getContainerSize() > 0
                    && WorldSpaces.at(vehicle).equals(cell))
            .stream().<Port<ItemStack>>map(WarfareAmmo::new).toList();
    }

    @Override
    public ResourceKind<ItemStack> resource() {
        return ResourceKind.ITEM;
    }

    @Override
    public Optional<UUID> entityId() {
        return Optional.of(vehicle.getUUID());
    }

    @Override
    public WorldPos anchor() {
        return WorldSpaces.at(vehicle);
    }

    @Override
    public String part() {
        return AMMO + "/" + vehicle.getUUID();
    }

    @Override
    public boolean valid(ServerLevel level) {
        return vehicle.level() == level && level.getEntity(vehicle.getUUID()) == vehicle && vehicle.isAlive()
            && !vehicle.isWreck() && vehicle.getContainerSize() > 0;
    }

    /** Loaded from beside the hull. */
    @Override
    public Optional<Stances> stance(ServerLevel level) {
        return Stances.of(level, VehicleBoarding.footings(vehicle));
    }

    @Override
    public List<ItemStack> contents(ServerLevel level) {
        return IntStream.range(0, vehicle.getContainerSize()).mapToObj(vehicle::getItem)
            .filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
    }

    @Override
    public ItemStack deposit(ServerLevel level, Mob writer, ItemStack offered, boolean simulate) {
        if (offered.isEmpty() || !valid(level)) {
            return ItemStack.EMPTY;
        }
        int left = offered.getCount();
        for (int slot = 0; slot < vehicle.getContainerSize() && left > 0; slot++) {
            ItemStack held = vehicle.getItem(slot);
            if (!vehicle.canPlaceItem(slot, offered)
                || !held.isEmpty() && !ItemStack.isSameItemSameComponents(held, offered)) {
                continue;
            }
            int room = Math.min(offered.getMaxStackSize(), vehicle.getMaxStackSize()) - held.getCount();
            int take = Math.min(room, left);
            if (take > 0) {
                if (!simulate) {
                    vehicle.setItem(slot, offered.copyWithCount(held.getCount() + take));
                }
                left -= take;
            }
        }
        if (!simulate && left < offered.getCount()) {
            vehicle.setChanged();
        }
        return left == offered.getCount() ? ItemStack.EMPTY : offered.copyWithCount(offered.getCount() - left);
    }

    @Override
    public ItemStack draw(ServerLevel level, Mob writer, ItemStack wanted, boolean simulate) {
        if (wanted.isEmpty() || !valid(level)) {
            return ItemStack.EMPTY;
        }
        int found = 0;
        for (int slot = 0; slot < vehicle.getContainerSize() && found < wanted.getCount(); slot++) {
            ItemStack held = vehicle.getItem(slot);
            if (!ItemStack.isSameItemSameComponents(held, wanted)) {
                continue;
            }
            int take = Math.min(held.getCount(), wanted.getCount() - found);
            if (!simulate) {
                vehicle.setItem(slot, held.copyWithCount(held.getCount() - take));
            }
            found += take;
        }
        if (!simulate && found > 0) {
            vehicle.setChanged();
        }
        return found == 0 ? ItemStack.EMPTY : wanted.copyWithCount(found);
    }
}
