package io.github.izakyl.folkways.core.resident.internal;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.Equipment.Demand;
import io.github.izakyl.folkways.core.colony.api.Equipment.Provider;
import io.github.izakyl.folkways.core.colony.api.Equipment;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** Real clothing slots, independent from borrowed tools and the copies shown in a working hand. */
public final class EquipmentCoordinator implements Equipment.Access {
    private static final String TAG = "folkways:managed_equipment";
    private static volatile Map<ResourceLocation, Provider> providers = Map.of();

    private EquipmentCoordinator() {
    }

    @Override
    public synchronized void register(ResourceLocation owner, Provider provider) {
        Ledger.writing(owner);
        Map<ResourceLocation, Provider> next = new LinkedHashMap<>(providers);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(provider)) != null) {
            throw new IllegalStateException(owner + " already supplies clothing demands");
        }
        providers = Collections.unmodifiableMap(next);
    }

    @Override
    public List<Demand> wanted(Colony colony, Body body) {
        Map<EquipmentSlot, Demand> chosen = new LinkedHashMap<>();
        for (Provider provider : providers.values()) {
            for (Demand demand : provider.wanted(colony, body)) {
                ItemStack worn = body.mob().getItemBySlot(demand.slot());
                // An unrelated player-given helmet is not permission to replace it or buy a spare
                // to hoard.
                if (worn.isEmpty() || managed(body, demand.slot()) || demand.matches(worn)) {
                    chosen.putIfAbsent(demand.slot(), demand);
                }
            }
        }
        return List.copyOf(chosen.values());
    }

    @Override
    public boolean reconcile(Colony colony, Body body) {
        if (body.mob().level().isClientSide()) {
            return false;
        }
        List<Demand> desired = wanted(colony, body);
        boolean changed = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                continue;
            }
            if (managed(body, slot)
                && desired.stream()
                .noneMatch(
                demand ->
                    demand.slot() == slot && demand.matches(body.mob().getItemBySlot(slot)))) {
                changed |= unwear(body, slot);
            }
        }
        for (Demand demand : desired) {
            if (!body.mob().getItemBySlot(demand.slot()).isEmpty()) {
                continue;
            }
            ItemStack taken = Containers.extract(body.pack(), demand::matches, 1);
            if (taken.isEmpty()) {
                continue;
            }
            try {
                body.mob().setItemSlot(demand.slot(), taken);
            } catch (RuntimeException | LinkageError broken) {
                if (!ItemStack.matches(body.mob().getItemBySlot(demand.slot()), taken)) {
                    giveBack(body, taken);
                    throw broken;
                }
                // Native equipping already happened; keeping the one physical item is safer than refunding it.
                org.slf4j.LoggerFactory.getLogger("folkways-equipment").warn("equipment changed before its event failed", broken);
            }
            CompoundTag held = body.mob().getPersistentData().getCompound(TAG);
            held.putString(demand.slot().getName(), BuiltInRegistries.ITEM.getKey(taken.getItem()).toString());
            body.mob().getPersistentData().put(TAG, held);
            // Mob's native armor death drops own the real item; no duplicate shadow lives in the pack.
            body.mob().setDropChance(demand.slot(), 2.0F);
            changed = true;
        }
        if (changed) {
            colony.inventoryFeedback().changedResident(body.id());
        }
        return changed;
    }

    @Override
    public boolean release(Body body) {
        boolean changed = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && managed(body, slot)) {
                changed |= unwear(body, slot);
            }
        }
        return changed;
    }

    @Override
    public boolean managed(Body body, EquipmentSlot slot) {
        CompoundTag held = body.mob().getPersistentData().getCompound(TAG);
        ItemStack stack = body.mob().getItemBySlot(slot);
        return !stack.isEmpty() && held.getString(slot.getName())
            .equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
    }

    private static boolean unwear(Body body, EquipmentSlot slot) {
        ItemStack stack = body.mob().getItemBySlot(slot);
        try {
            body.mob().setItemSlot(slot, ItemStack.EMPTY);
        } catch (RuntimeException | LinkageError broken) {
            if (!body.mob().getItemBySlot(slot).isEmpty()) {
                throw broken;
            }
            org.slf4j.LoggerFactory.getLogger("folkways-equipment").warn("equipment removed before its event failed", broken);
        }
        CompoundTag held = body.mob().getPersistentData().getCompound(TAG);
        held.remove(slot.getName());
        body.mob().getPersistentData().put(TAG, held);
        giveBack(body, stack);
        return true;
    }

    private static void giveBack(Body body, ItemStack stack) {
        ItemStack left = Containers.insert(body.pack(), stack);
        if (!left.isEmpty()) {
            body.mob().spawnAtLocation(left);
        }
    }

    public static void install() {
        Equipment.install(new EquipmentCoordinator());
    }
}
