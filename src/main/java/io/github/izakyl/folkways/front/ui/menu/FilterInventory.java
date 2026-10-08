package io.github.izakyl.folkways.front.ui.menu;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class FilterInventory extends SimpleContainer {

    public interface Sink {
        void itemsChanged(FilterInventory grid);
    }

    public FilterInventory(int size) {
        super(size);
    }

    public Optional<ItemFilter> filterAt(int index) {
        return FilterStacks.parse(getItem(index));
    }

    public List<ItemFilter> filters() {
        return IntStream.range(0, getContainerSize())
            .mapToObj(this::filterAt)
            .flatMap(Optional::stream)
            .toList();
    }

    public void put(int index, ItemFilter filter) {
        setItem(index, FilterStacks.of(filter));
    }

    @Override
    public boolean canPlaceItem(int index, ItemStack stack) {
        return FilterStacks.parse(stack).filter(FilterInventory::real).isPresent();
    }

    private static boolean real(ItemFilter filter) {
        return filter.tag() || BuiltInRegistries.ITEM.getOptional(filter.id())
            .filter(item -> item != Items.AIR)
            .isPresent();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public int getMaxStackSize(ItemStack stack) {
        return 1;
    }
}
