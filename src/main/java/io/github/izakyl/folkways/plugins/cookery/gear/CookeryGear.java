package io.github.izakyl.folkways.plugins.cookery.gear;

import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.resident.body.Body;
import io.github.izakyl.folkways.core.api.resident.body.Equipment;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.plugins.farming.FarmingContent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;

public final class CookeryGear {
    private CookeryGear() {
    }

    public static void register(BiPredicate<Colony, Body> wearHat, BiPredicate<Colony, Body> wearArmor,
                                BiPredicate<Colony, Item> allowed) {
        Equipment.register(ResourceLocation.fromNamespaceAndPath("folkways", "cookery_gear"), (colony, body) -> {
            if (body.licences().of(FarmingContent.trade()).isEmpty()) return List.of();
            List<Equipment.Demand> wanted = new ArrayList<>();
            if (wearHat.test(colony, body)) {
                List<ItemSpec> hats = Goods.members(ItemSpec.of(TagMod.STRAW_HAT)).stream()
                    .filter(item -> allowed.test(colony, item))
                    .map(item -> ItemSpec.of(BuiltInRegistries.ITEM.getKey(item))).toList();
                if (!hats.isEmpty()) wanted.add(new Equipment.Demand(EquipmentSlot.HEAD, ItemSpec.anyOf(hats)));
            }
            if (wearArmor.test(colony, body)) {
                add(wanted, colony, allowed, EquipmentSlot.CHEST, ModItems.FARMER_CHEST_PLATE.get());
                add(wanted, colony, allowed, EquipmentSlot.LEGS, ModItems.FARMER_LEGGINGS.get());
                add(wanted, colony, allowed, EquipmentSlot.FEET, ModItems.FARMER_BOOTS.get());
            }
            return List.copyOf(wanted);
        });
    }

    private static void add(List<Equipment.Demand> wanted, Colony colony, BiPredicate<Colony, Item> allowed,
                            EquipmentSlot slot, Item item) {
        if (allowed.test(colony, item)) {
            wanted.add(new Equipment.Demand(slot, ItemSpec.of(BuiltInRegistries.ITEM.getKey(item))));
        }
    }
}
