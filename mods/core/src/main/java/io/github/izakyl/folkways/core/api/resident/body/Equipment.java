package io.github.izakyl.folkways.core.api.resident.body;

import io.github.izakyl.folkways.base.Ledger;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
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
public final class Equipment {
    private static final String TAG = "folkways:managed_equipment";
    private static volatile Map<ResourceLocation, Provider> providers = Map.of();

    @FunctionalInterface
    public interface Provider {
        List<Demand> wanted(Colony colony, Body body);
    }

    public record Demand(EquipmentSlot slot, ItemSpec items) {
        public Demand {
            Objects.requireNonNull(slot);
            Objects.requireNonNull(items);
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                throw new IllegalArgumentException("clothing needs an armor slot, not " + slot);
            }
        }
    }

    private Equipment() {
    }

    public static synchronized void register(ResourceLocation owner, Provider provider) {
        Ledger.writing(owner);
        Map<ResourceLocation, Provider> next = new LinkedHashMap<>(providers);
        if (next.putIfAbsent(Objects.requireNonNull(owner), Objects.requireNonNull(provider)) != null) {
            throw new IllegalStateException(owner + " already supplies clothing demands");
        }
        providers = Collections.unmodifiableMap(next);
    }

    public static List<Demand> wanted(Colony colony, Body body) {
        Map<EquipmentSlot, Demand> chosen = new LinkedHashMap<>();
        for (Provider provider : providers.values()) {
            for (Demand demand : provider.wanted(colony, body)) {
                ItemStack worn = body.mob().getItemBySlot(demand.slot());
                // An unrelated player-given helmet is not permission to replace it or buy a spare to hoard.
                if (worn.isEmpty() || managed(body, demand.slot()) || Goods.matches(worn, demand.items())) {
                    chosen.putIfAbsent(demand.slot(), demand);
                }
            }
        }
        return List.copyOf(chosen.values());
    }

    public static long worn(Body body, ItemSpec items) {
        long count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                ItemStack stack = body.mob().getItemBySlot(slot);
                if (Goods.matches(stack, items)) count += stack.getCount();
            }
        }
        return count;
    }

    public static long carried(Body body, Demand demand) {
        ItemStack inSlot = body.mob().getItemBySlot(demand.slot());
        return Goods.countIn(body.pack(), demand.items())
            + (Goods.matches(inSlot, demand.items()) ? inSlot.getCount() : 0);
    }

    public static boolean reconcile(Colony colony, Body body) {
        if (body.mob().level().isClientSide()) return false;
        List<Demand> desired = wanted(colony, body);
        boolean changed = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) continue;
            if (managed(body, slot) && desired.stream().noneMatch(demand -> demand.slot() == slot
                    && Goods.matches(body.mob().getItemBySlot(slot), demand.items()))) {
                changed |= unwear(body, slot);
            }
        }
        for (Demand demand : desired) {
            if (!body.mob().getItemBySlot(demand.slot()).isEmpty()) continue;
            ItemStack taken = Containers.extract(body.pack(), stack -> Goods.matches(stack, demand.items()), 1);
            if (taken.isEmpty()) continue;
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
        return changed;
    }

    public static boolean release(Body body) {
        boolean changed = false;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && managed(body, slot)) {
                changed |= unwear(body, slot);
            }
        }
        return changed;
    }

    public static boolean managed(Body body, EquipmentSlot slot) {
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
            if (!body.mob().getItemBySlot(slot).isEmpty()) throw broken;
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
        if (!left.isEmpty()) body.mob().spawnAtLocation(left);
    }
}
