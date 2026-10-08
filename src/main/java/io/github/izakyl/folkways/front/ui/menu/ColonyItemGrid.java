package io.github.izakyl.folkways.front.ui.menu;

import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;

public final class ColonyItemGrid implements FilterInventory.Sink {

    private final Supplier<Optional<Colony>> colony;
    private Optional<ResourceLocation> scope = Optional.empty();
    private String key = "";
    private boolean binding;

    public ColonyItemGrid(Supplier<Optional<Colony>> colony) {
        this.colony = colony;
    }

    public void show(FilterInventory grid, Optional<ResourceLocation> scope, String key) {
        binding = true;
        try {
            this.scope = scope;
            this.key = key;
            grid.clearContent();
            scope.ifPresent(present -> colony.get().ifPresent(held -> {
                List<ItemFilter> entries = ColonyFront.of(held).settings(present).entries(key);
                for (int index = 0; index < entries.size() && index < grid.getContainerSize(); index++) {
                    grid.put(index, entries.get(index));
                }
            }));
        } finally {
            binding = false;
        }
    }

    @Override
    public void itemsChanged(FilterInventory grid) {
        if (binding) {
            return;
        }
        scope.ifPresent(present -> colony.get().ifPresent(held ->
            ColonyFront.of(held).setSetting(present, key,
                new ColonySettings.Value.Items(grid.filters()))));
    }
}
