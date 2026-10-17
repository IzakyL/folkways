package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.Optional;
import net.minecraft.world.item.ItemStack;

// An item filter held as a stack: the item itself, or the colony's tag-filter item for a tag.
public final class FilterStacks {

    public interface Codec {

        Optional<ItemFilter> parse(ItemStack stack);

        ItemStack of(ItemFilter filter);
    }

    private static Codec codec = new Codec() {
        public Optional<ItemFilter> parse(ItemStack stack) {
            throw new IllegalStateException("filter stacks have not been installed");
        }

        public ItemStack of(ItemFilter filter) {
            throw new IllegalStateException("filter stacks have not been installed");
        }
    };

    private FilterStacks() {
    }

    public static void install(Codec installed) {
        codec = installed;
    }

    public static Optional<ItemFilter> parse(ItemStack stack) {
        return codec.parse(stack);
    }

    public static ItemStack of(ItemFilter filter) {
        return codec.of(filter);
    }
}
