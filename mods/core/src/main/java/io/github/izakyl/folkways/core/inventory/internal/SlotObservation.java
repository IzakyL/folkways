package io.github.izakyl.folkways.core.inventory.internal;

import io.github.izakyl.folkways.core.inventory.api.SlotSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

final class SlotObservation {

    private final Map<Integer, ItemStack> identities = new HashMap<>();
    private final Map<Integer, Long> slotIncarnations = new HashMap<>();
    private final LongSupplier incarnations;

    SlotObservation(LongSupplier incarnations) {
        this.incarnations = incarnations;
    }

    long identify(int physical, ItemStack stack) {
        if (identities.get(physical) != stack || !slotIncarnations.containsKey(physical)) {
            identities.put(physical, stack);
            slotIncarnations.put(physical, incarnations.getAsLong());
        }
        return slotIncarnations.get(physical);
    }

    SlotSnapshot capture(int physical, int accessible, ItemStack stack, int limit) {
        return new SlotSnapshot(physical < 0 ? -1 - physical : physical, accessible, stack, limit,
            identify(physical, stack));
    }

    static boolean sameIdentities(List<SlotSnapshot> one, List<SlotSnapshot> other) {
        if (one.size() != other.size()) {
            return false;
        }
        Map<Integer, Long> identities = new HashMap<>();
        one.forEach(slot -> identities.put(slot.physicalSlot(), slot.incarnation()));
        return other.stream().allMatch(slot -> identities.getOrDefault(slot.physicalSlot(), -1L)
            == slot.incarnation());
    }

    static boolean sameEquipment(Map<EquipmentSlot, SlotSnapshot> one,
                                 Map<EquipmentSlot, SlotSnapshot> other) {
        return one.keySet().equals(other.keySet()) && one.entrySet().stream().allMatch(entry ->
            ItemStack.matches(entry.getValue().stackCopy(), other.get(entry.getKey()).stackCopy()));
    }

    static boolean sameSlots(List<SlotSnapshot> one, List<SlotSnapshot> other) {
        if (one.size() != other.size()) {
            return false;
        }
        for (int slot = 0; slot < one.size(); slot++) {
            if (!ItemStack.matches(one.get(slot).stackCopy(), other.get(slot).stackCopy())) {
                return false;
            }
        }
        return true;
    }
}
