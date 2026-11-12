package io.github.izakyl.folkways.core.equipment.api;

import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * One place an equipment profile can fill with a single item: a body slot, horse armour or an accessory. The slot
 * says what fits and what is worn there now; the module that registered it supplies and equips the item.
 */
public interface GearSlot {
    ResourceLocation id();

    /** The {@link GearGroup} the page shows the slot in. */
    ResourceLocation group();

    /** Position inside its group, smallest first. */
    default int order() {
        return 0;
    }

    Component name();

    /** A texture drawn faintly in the slot while nothing is configured, such as the vanilla empty-helmet outline. */
    default Optional<ResourceLocation> emptyIcon() {
        return Optional.empty();
    }

    default boolean atlasIcon() {
        return false;
    }

    /** Whether a stack may be configured for this slot and counts as worn there. */
    boolean fits(ItemStack stack);

    /** Rechecks entity-dependent framework rules when a resident is loaded. */
    default boolean fits(Body body, ItemStack stack) {
        return fits(stack);
    }

    /** Whether Core supplies this slot, rather than a separate supplier such as the patrol. */
    default boolean riding() {
        return false;
    }

    default boolean personal() {
        return true;
    }

    /** Equips a real stack. The caller transfers ownership only after a successful write. */
    default void equip(Body body, ItemStack stack) {
        dress(body.mob(), stack);
    }

    default boolean removable(Body body) {
        return true;
    }

    /** What the body has in this slot now, or empty when nothing or unknown; read on the server. */
    default ItemStack worn(Body body) {
        return ItemStack.EMPTY;
    }

    /** Ready-made choices offered beside the general item picker, such as weapon variants; empty for none. */
    default List<ItemStack> choices() {
        return List.of();
    }

    /** Shows the configured stack on the page's preview figure; client only. */
    default void dress(LivingEntity figure, ItemStack stack) {
    }

    static GearSlot of(ResourceLocation id, ResourceLocation group, int order, Component name,
                       Optional<ResourceLocation> emptyIcon, Predicate<ItemStack> fits,
                       Function<Body, ItemStack> worn, BiConsumer<LivingEntity, ItemStack> dress) {
        return new GearSlot() {
            @Override
            public ResourceLocation id() {
                return id;
            }

            @Override
            public ResourceLocation group() {
                return group;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public Component name() {
                return name;
            }

            @Override
            public Optional<ResourceLocation> emptyIcon() {
                return emptyIcon;
            }

            @Override
            public boolean fits(ItemStack stack) {
                return !stack.isEmpty() && fits.test(stack);
            }

            @Override
            public ItemStack worn(Body body) {
                return worn.apply(body);
            }

            @Override
            public void dress(LivingEntity figure, ItemStack stack) {
                dress.accept(figure, stack);
            }
        };
    }
}
