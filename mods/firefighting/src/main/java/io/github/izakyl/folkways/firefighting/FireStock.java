package io.github.izakyl.folkways.firefighting;

import com.mikoalopex.createfirefightingadd.CreateFireFightingAdd;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction;

final class FireStock {
    private record StoreFacts(boolean extinguisher, boolean water) { }

    private final Colony colony;
    private InventorySnapshot.Version version;
    private final Map<UUID, ItemStack> residents = new LinkedHashMap<>();
    private final Map<Stash, StoreFacts> stores = new LinkedHashMap<>();

    FireStock(Colony colony) {
        this.colony = colony;
    }

    void staleResident(UUID id) {
        InventorySnapshot.Resident resident = colony.inventory().snapshot().residents().get(id);
        if (resident != null && resident.state() == InventorySnapshot.State.OBSERVED) {
            colony.inventoryFeedback().staleResident(id, resident.version());
        }
    }

    Optional<ItemStack> resident(UUID id) {
        InventorySnapshot snapshot = colony.inventory().snapshot();
        update(snapshot);
        InventorySnapshot.Resident resident = snapshot.residents().get(id);
        if (resident == null || resident.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObservedResident(id);
            return Optional.empty();
        }
        return Optional.of(residents.getOrDefault(id, ItemStack.EMPTY).copy());
    }

    Optional<Boolean> extinguisher(InventorySnapshot snapshot, InventorySnapshot.Store store) {
        update(snapshot);
        if (store.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObserved(store.stash());
            return Optional.empty();
        }
        StoreFacts facts = stores.get(store.stash());
        return facts == null ? Optional.empty() : Optional.of(facts.extinguisher());
    }

    Optional<Boolean> water(InventorySnapshot snapshot, InventorySnapshot.Store store) {
        update(snapshot);
        if (store.state() != InventorySnapshot.State.OBSERVED) {
            colony.inventory().ensureObserved(store.stash());
            return Optional.empty();
        }
        StoreFacts facts = stores.get(store.stash());
        return facts == null ? Optional.empty() : Optional.of(facts.water());
    }

    private void update(InventorySnapshot snapshot) {
        InventorySnapshot.Version next = snapshot.version();
        if (version != null && version.epoch().equals(next.epoch()) && version.contents() == next.contents()
            && version.topology() == next.topology() && version.policy() == next.policy()
            && version.coverage() == next.coverage()) {
            return;
        }
        residents.clear();
        stores.clear();
        for (InventorySnapshot.Resident resident : snapshot.residents().values()) {
            for (SlotSnapshot slot : resident.pack()) {
                ItemStack stack = slot.stackCopy();
                if (stack.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get())) {
                    residents.put(resident.resident(), stack);
                    break;
                }
            }
        }
        for (InventorySnapshot.Store store : snapshot.stores().values()) {
            boolean extinguisher = false;
            boolean water = false;
            for (SlotSnapshot slot : store.slots()) {
                if (slot.accessibleSlot().isEmpty()) {
                    continue;
                }
                ItemStack stack = slot.stackCopy();
                extinguisher |= stack.is(CreateFireFightingAdd.FIRE_EXTINGUISHER_ITEM.get());
                var fluid = stack.getCapability(Capabilities.FluidHandler.ITEM);
                if (fluid != null && !fluid.drain(new FluidStack(Fluids.WATER, 1000), FluidAction.SIMULATE)
                    .isEmpty()) {
                    water = true;
                }
            }
            stores.put(store.stash(), new StoreFacts(extinguisher, water));
        }
        version = next;
    }
}
