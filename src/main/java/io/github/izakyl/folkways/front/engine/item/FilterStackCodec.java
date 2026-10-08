package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class FilterStackCodec implements FilterStacks.Codec {

    @Override
    public Optional<ItemFilter> parse(ItemStack stack) {
        if (stack.isEmpty()) {
            return Optional.empty();
        }
        if (stack.is(FolkwaysItems.TAG_FILTER.get())) {
            return Optional.ofNullable(stack.get(FolkwaysDataComponents.FILTER_TAG.get())).map(ItemFilter::tag);
        }
        return Optional.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(stack.getItem())));
    }

    @Override
    public ItemStack of(ItemFilter filter) {
        if (filter.tag()) {
            ItemStack stack = new ItemStack(FolkwaysItems.TAG_FILTER.get());
            stack.set(FolkwaysDataComponents.FILTER_TAG.get(), filter.id());
            return stack;
        }
        return BuiltInRegistries.ITEM.getOptional(filter.id())
            .filter(item -> item != Items.AIR)
            .map(ItemStack::new)
            .orElse(ItemStack.EMPTY);
    }
}
