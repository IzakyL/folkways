package io.github.izakyl.folkways.core.equipment.internal;

import io.github.izakyl.folkways.core.equipment.api.EquipmentProfile;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/** Framework slots keep real equipment in their own capability; this tag records only which slots we manage. */
final class NamedEquipment {
    private static final String TAG = "folkways_core:managed_accessories";

    private NamedEquipment() {
    }

    static void reconcile(Body body, List<EquipmentProfile.Entry> entries) {
        CompoundTag managed = body.mob().getPersistentData().getCompound(TAG);
        for (var gear : GearSlots.all()) {
            if (!gear.personal() || GearSlots.equipment(gear.id()).isPresent()) {
                continue;
            }
            var desired = entries.stream().filter(entry -> entry.slot().filter(gear.id()::equals).isPresent())
                .findFirst();
            ItemStack worn = gear.worn(body);
            String key = gear.id().toString();
            boolean ours = !worn.isEmpty() && managed.getString(key)
                .equals(BuiltInRegistries.ITEM.getKey(worn.getItem()).toString());
            if (ours && desired.filter(entry -> entry.matches(worn) && gear.fits(body, worn)).isEmpty()
                && gear.removable(body)) {
                gear.equip(body, ItemStack.EMPTY);
                ItemStack rest = body.pack().insert(worn);
                if (!rest.isEmpty()) {
                    body.mob().spawnAtLocation(rest);
                }
                managed.remove(key);
            }
            if (gear.worn(body).isEmpty() && desired.isPresent()) {
                ItemStack taken = Containers.extract(body.pack(), stack -> desired.get().matches(stack)
                    && gear.fits(body, stack), 1);
                if (!taken.isEmpty()) {
                    try {
                        gear.equip(body, taken);
                    } catch (RuntimeException | LinkageError broken) {
                        if (!ItemStack.matches(gear.worn(body), taken)) {
                            ItemStack rest = body.pack().insert(taken);
                            if (!rest.isEmpty()) {
                                body.mob().spawnAtLocation(rest);
                            }
                            throw broken;
                        }
                    }
                    managed.putString(key, BuiltInRegistries.ITEM.getKey(taken.getItem()).toString());
                }
            }
        }
        body.mob().getPersistentData().put(TAG, managed);
    }
}
