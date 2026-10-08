package io.github.izakyl.folkways.front.engine.item;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysDataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class TagFilterItem extends Item {
    public TagFilterItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        ResourceLocation tagId = stack.get(FolkwaysDataComponents.FILTER_TAG.get());
        return tagId == null ? super.getName(stack) : Component.literal(ItemFilter.tag(tagId).describe());
    }
}
