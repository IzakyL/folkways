package io.github.izakyl.folkways.vanilla.combat.weapon;

import io.github.izakyl.folkways.core.equipment.api.GearGroup;
import io.github.izakyl.folkways.core.equipment.api.GearSlot;
import io.github.izakyl.folkways.core.equipment.api.GearSlots;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.AnimalArmorItem;
import net.minecraft.world.item.ItemStack;

/** A rider's horse is equipped through the profile too: its saddle and its armour, on a pasture horse. */
public final class MountGear {
    public static final ResourceLocation GROUP = ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "riding");
    public static final ResourceLocation HORSE_ARMOR =
        ResourceLocation.fromNamespaceAndPath("folkways_vanilla", "horse_armor");

    private MountGear() {
    }

    public static void install() {
        GearSlots.register(new GearGroup(GROUP, Component.translatable("folkways.equipment.group.mount"),
            GearGroup.Placement.RIGHT, 10));
        GearSlots.register(new GearSlot() {
            @Override
            public ResourceLocation id() {
                return HORSE_ARMOR;
            }

            @Override
            public ResourceLocation group() {
                return GROUP;
            }

            @Override
            public Component name() {
                return Component.translatable("folkways.equipment.slot.horse_armor");
            }

            @Override
            public boolean fits(ItemStack stack) {
                return horseArmor(stack);
            }

            @Override
            public boolean riding() {
                return true;
            }

            @Override
            public boolean personal() {
                return false;
            }

            @Override
            public ItemStack worn(io.github.izakyl.folkways.core.resident.api.Body body) {
                return ResidentMounts.horse(body.mob()).map(horse -> horse.getItemBySlot(EquipmentSlot.BODY))
                    .orElse(ItemStack.EMPTY);
            }
        });
    }

    public static boolean horseArmor(ItemStack stack) {
        return stack.getItem() instanceof AnimalArmorItem armor
            && armor.getBodyType() == AnimalArmorItem.BodyType.EQUESTRIAN;
    }
}
