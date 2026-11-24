package io.github.izakyl.folkways.core.shell.carts.api;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;

/** Exact stock, independent of Redux's deliberately approximate cargo models and the open inventory menu. */
public final class CartContents {
    public interface View {
        CompoundTag folkways$contents();

        void folkways$contents(CompoundTag contents);
    }

    private CartContents() {
    }

    public static void synchronize(AbstractCargoCart cart) {
        if (cart.level().isClientSide()) {
            return;
        }
        CompoundTag contents = new CompoundTag();
        for (var stack : cart.getItemStacks()) {
            if (!stack.isEmpty()) {
                String item = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                contents.putLong(item, contents.getLong(item) + stack.getCount());
            }
        }
        ((View) cart).folkways$contents(contents);
    }
}
