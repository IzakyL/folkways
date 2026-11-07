package io.github.izakyl.folkways.core.inventory.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;

// Server-thread notifications. They enqueue work; they never read an inventory synchronously.
public interface InventoryFeedback {

    InventoryFeedback NONE = new InventoryFeedback() { };

    static void store(ServerLevel level, WorldPos at) {
        NeoForge.EVENT_BUS.post(new InventoryChanged.Store(level, at));
    }

    static void resident(Entity entity) {
        NeoForge.EVENT_BUS.post(new InventoryChanged.Resident(entity));
    }

    record StoreSlot(Stash store, int physicalSlot) {
    }

    default void stale(StoreSlot slot, InventorySnapshot.Version observed) {
    }

    default void stale(Stash store, InventorySnapshot.Version observed) {
    }

    default void staleResident(UUID resident, InventorySnapshot.Version observed) {
    }

    default void staleApproach(Stash store, InventorySnapshot.Version observed) {
    }

    default void changed(Stash store) {
    }

    default void providersChanged(ResourceLocation owner) {
    }

    // Register a stack already moved by execution; no inventory is opened or synchronously refreshed.
    default long changedResidentSlot(UUID resident, int physicalSlot, ItemStack stack) {
        changedResident(resident);
        return 0;
    }

    default long changedEquipment(UUID resident, EquipmentSlot slot, ItemStack stack) {
        changedResident(resident);
        return 0;
    }

    default void changedResident(UUID resident) {
    }
}
