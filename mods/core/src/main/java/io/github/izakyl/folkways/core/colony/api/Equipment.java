package io.github.izakyl.folkways.core.colony.api;

import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.ItemStack;

/** Real clothing slots, independent from borrowed tools and the copies shown in a working hand. */
public final class Equipment {

    @FunctionalInterface
    public interface Provider {
        List<Demand> wanted(Colony colony, Body body);
    }

    public record Demand(EquipmentSlot slot, ItemSpec items, boolean automaticSupply) {
        public boolean matches(ItemStack stack) {
            EquipmentSlot custom = stack.getEquipmentSlot();
            Equipable equipable = Equipable.get(stack);
            EquipmentSlot actual =
                custom != null ? custom : equipable == null ? EquipmentSlot.MAINHAND : equipable.getEquipmentSlot();
            return actual == slot && Goods.matches(stack, items);
        }

        public Demand(EquipmentSlot slot, ItemSpec items) {
            this(slot, items, true);
        }

        public Demand {
            Objects.requireNonNull(slot);
            Objects.requireNonNull(items);
            if (slot.getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
                throw new IllegalArgumentException("clothing needs an armor slot, not " + slot);
            }
        }
    }

    public interface Access {

        void register(ResourceLocation owner, Provider provider);

        List<Demand> wanted(Colony colony, Body body);

        boolean reconcile(Colony colony, Body body);

        boolean release(Body body);

        boolean managed(Body body, EquipmentSlot slot);
    }

    private static Access access;

    private Equipment() {
    }

    public static void install(Access installed) {
        access = Objects.requireNonNull(installed);
    }

    public static void register(ResourceLocation owner, Provider provider) {
        access.register(owner, provider);
    }

    public static List<Demand> wanted(Colony colony, Body body) {
        return access.wanted(colony, body);
    }

    public static long worn(Body body, ItemSpec items) {
        long count = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR) {
                ItemStack stack = body.mob().getItemBySlot(slot);
                if (Goods.matches(stack, items)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    public static long carried(Body body, Demand demand) {
        ItemStack inSlot = body.mob().getItemBySlot(demand.slot());
        return body.pack().contents().stream().filter(demand::matches).mapToLong(ItemStack::getCount).sum()
            + (demand.matches(inSlot) ? inSlot.getCount() : 0);
    }

    public static boolean reconcile(Colony colony, Body body) {
        return access.reconcile(colony, body);
    }

    public static boolean release(Body body) {
        return access.release(body);
    }

    public static boolean managed(Body body, EquipmentSlot slot) {
        return access.managed(body, slot);
    }
}
