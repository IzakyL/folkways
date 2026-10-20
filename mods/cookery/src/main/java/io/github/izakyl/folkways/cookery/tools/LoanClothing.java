package io.github.izakyl.folkways.cookery.tools;

import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Worker;
import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/** Slot ownership names a physical loan, never a player's pre-existing item. */
final class LoanClothing {
    private static final String KEY = "folkways:cookery_clothing";
    private static final String ITEMS = "folkways:cookery_clothing_items";

    private LoanClothing() {
    }

    static boolean borrowedSlot(Mob body, EquipmentSlot slot) {
        return body.getPersistentData().getCompound(KEY).hasUUID(slot.getName());
    }

    static Optional<EquipmentSlot> slot(TaskTools service, Mob body, KitchenTools.Lease lease) {
        return slot(service.tool(lease).orElse(ItemStack.EMPTY), body, lease);
    }

    private static Optional<EquipmentSlot> slot(ItemStack expected, Mob body, KitchenTools.Lease lease) {
        CompoundTag worn = body.getPersistentData().getCompound(KEY);
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (!worn.hasUUID(slot.getName()) || !worn.getUUID(slot.getName()).equals(lease.id())) continue;
            ItemStack actual = body.getItemBySlot(slot);
            if (same(actual, expected)) return Optional.of(slot);
            // A player replaced the item in this slot. Its stale ownership never claims the replacement.
            unmark(body, slot);
        }
        return Optional.empty();
    }

    static ItemStack held(TaskTools service, Mob body, KitchenTools.Lease lease) {
        return slot(service, body, lease).map(body::getItemBySlot).orElse(ItemStack.EMPTY);
    }

    static ItemStack held(ItemStack expected, Mob body, KitchenTools.Lease lease) {
        return slot(expected, body, lease).map(body::getItemBySlot).orElse(ItemStack.EMPTY);
    }

    static boolean equip(TaskTools service, Worker who, Map<EquipmentSlot, KitchenTools.Lease> clothes) {
        // Preflight the whole set before changing any slot. Reservation already covers every missing piece.
        for (var entry : clothes.entrySet()) {
            KitchenTools.Lease lease = entry.getValue();
            if (slot(service, who.body(), lease).filter(entry.getKey()::equals).isPresent()) continue;
            if (!who.body().getItemBySlot(entry.getKey()).isEmpty()
                    || service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY)) < 0) return false;
        }
        CompoundTag worn = who.body().getPersistentData().getCompound(KEY);
        CompoundTag items = who.body().getPersistentData().getCompound(ITEMS);
        for (var entry : clothes.entrySet()) {
            KitchenTools.Lease lease = entry.getValue();
            if (slot(service, who.body(), lease).filter(entry.getKey()::equals).isPresent()) continue;
            int source = service.carriedSlot(who.pack(), lease, service.tool(lease).orElse(ItemStack.EMPTY));
            ItemStack actual = who.pack().removeItem(source, 1);
            who.body().setItemSlot(entry.getKey(), actual);
            who.body().setDropChance(entry.getKey(), 2.0F);
            worn.putUUID(entry.getKey().getName(), lease.id());
            items.put(entry.getKey().getName(), actual.save(who.body().registryAccess()));
        }
        if (!worn.isEmpty()) who.body().getPersistentData().put(KEY, worn);
        if (!items.isEmpty()) who.body().getPersistentData().put(ITEMS, items);
        who.pack().setChanged();
        return true;
    }

    static boolean recover(TaskTools service, KitchenTools.Lease lease, Body body) {
        var slot = slot(service, body.mob(), lease);
        if (slot.isEmpty()) return true;
        ItemStack actual = body.mob().getItemBySlot(slot.get());
        // Do not drop or destroy clothing when cargo is temporarily full. Recovery waits for real room.
        if (!ToolStock.room(body.pack(), actual)) return false;
        body.mob().setItemSlot(slot.get(), ItemStack.EMPTY);
        ItemStack left = Containers.insert(body.pack(), actual);
        if (!left.isEmpty()) {
            body.mob().setItemSlot(slot.get(), left);
            return false;
        }
        unmark(body.mob(), slot.get());
        service.record(lease, actual);
        return true;
    }

    static void release(TaskTools service, KitchenTools.Lease lease, Body body) {
        if (recover(service, lease, body)) return;
        // A razed colony cannot wait for cargo space: preserve its exact loan before the body leaves.
        slot(service, body.mob(), lease).ifPresent(slot -> release(body, slot));
    }

    static void joined(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Bodies.of(event.getEntity()).filter(body -> body.colonyId()
            .filter(id -> Colonies.razed(level.getServer(), id)).isPresent()).ifPresent(body -> {
                // The colony's service may have closed while this body was unloaded. Its saved identity
                // snapshot permits only the original physical garment to leave the native slot on reload.
                CompoundTag worn = body.mob().getPersistentData().getCompound(KEY);
                CompoundTag items = body.mob().getPersistentData().getCompound(ITEMS);
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    if (!worn.hasUUID(slot.getName())) continue;
                    ItemStack expected = ItemStack.parseOptional(body.mob().registryAccess(), items.getCompound(slot.getName()));
                    if (same(body.mob().getItemBySlot(slot), expected)) release(body, slot);
                    else unmark(body.mob(), slot);
                }
            });
    }

    private static void release(Body body, EquipmentSlot slot) {
        ItemStack actual = body.mob().getItemBySlot(slot);
        body.mob().setItemSlot(slot, ItemStack.EMPTY);
        unmark(body.mob(), slot);
        ItemStack left = Containers.insert(body.pack(), actual);
        if (!left.isEmpty()) body.mob().spawnAtLocation(left);
    }

    private static boolean same(ItemStack actual, ItemStack expected) {
        ItemStack compared = actual.copy();
        // Armor's native durability can change between actions. Other components identify the loan.
        if (expected.isDamageableItem() && compared.isDamageableItem()) compared.setDamageValue(expected.getDamageValue());
        return !expected.isEmpty() && ItemStack.isSameItemSameComponents(compared, expected);
    }

    private static void unmark(Mob body, EquipmentSlot slot) {
        for (String key : java.util.List.of(KEY, ITEMS)) {
            CompoundTag held = body.getPersistentData().getCompound(key);
            held.remove(slot.getName());
            if (held.isEmpty()) body.getPersistentData().remove(key);
            else body.getPersistentData().put(key, held);
        }
    }
}
