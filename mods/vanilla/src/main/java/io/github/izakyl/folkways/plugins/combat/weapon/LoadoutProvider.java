package io.github.izakyl.folkways.plugins.combat.weapon;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Personal stock supplied through the same finite, local collection work as ordinary patrol gear. */
public interface LoadoutProvider {
    record Choice(CompoundTag options, Component label) {
        public Choice { options = options.copy(); }
        @Override public CompoundTag options() { return options.copy(); }
    }

    Component label();
    ItemSpec weapon(CompoundTag options);
    List<Amount> ammunition(CompoundTag options);
    Optional<CompoundTag> infer(ItemStack weapon);

    /** Refine the goods identity when only some physical variants are usable equipment. */
    default boolean acceptsWeapon(ItemStack stack, CompoundTag options) { return Goods.matches(stack, weapon(options)); }
    default boolean valid(CompoundTag options) { return options.isEmpty(); }
    default List<Choice> choices() { return List.of(new Choice(new CompoundTag(), label())); }
    default int lowWater(CompoundTag options, Amount ammunition) { return (int) ammunition.count() - 1; }
    default int loaded(ItemStack weapon, ItemSpec ammunition) { return 0; }
    default Component detail(ResidentEntity body, CompoundTag options) { return Component.empty(); }
}
