package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepository;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryBuilder;
import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryFilter;
import com.refinedmods.refinedstorage.common.api.grid.Grid;
import com.refinedmods.refinedstorage.common.api.grid.view.GridResource;
import com.refinedmods.refinedstorage.common.grid.AbstractGridContainerMenu;
import com.refinedmods.refinedstorage.common.grid.GridData;
import com.refinedmods.refinedstorage.common.grid.GridSortingTypes;
import io.github.izakyl.folkways.core.shell.rs2.TerminalRecipes;
import java.util.List;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractGridContainerMenu.class, remap = false)
public abstract class Rs2TerminalMenuMixin implements TerminalRecipes.Menu {

    @Shadow @Final
    protected Inventory playerInventory;
    @Shadow
    private Grid grid;
    @Shadow @Final @Mutable
    private ResourceRepository<GridResource> repository;
    @Unique
    private Set<?> folkways$sent;

    @Shadow
    private static ResourceRepositoryBuilder<GridResource> createRepositoryBuilder(
            GridSortingTypes.TrackedResourceProvider provider) {
        throw new AssertionError();
    }

    @Shadow
    private ResourceRepositoryFilter<GridResource> createBaseFilter() {
        throw new AssertionError();
    }

    @Inject(method = "broadcastChanges", at = @At("TAIL"))
    private void folkways$patterns(CallbackInfo callback) {
        if (playerInventory.player instanceof ServerPlayer player && grid != null) {
            var data = TerminalRecipes.filter(player, grid.getItemStorage(),
                new GridData(grid.isGridActive(), List.of(), grid.getAutocraftableResources()));
            if (!data.autocraftableResources().equals(folkways$sent)) {
                folkways$sent = Set.copyOf(data.autocraftableResources());
                folkways$refresh();
            }
        }
    }

    @Override
    public void folkways$refresh() {
        if (playerInventory.player instanceof ServerPlayer player && grid != null) {
            TerminalRecipes.send(player, grid, ((AbstractGridContainerMenu) (Object) this).containerId);
        }
    }

    @Override
    public void folkways$receive(GridData data) {
        var menu = (AbstractGridContainerMenu) (Object) this;
        Runnable listener = ((Rs2RepositoryAccessor) repository).folkways$listener();
        var builder = createRepositoryBuilder(menu);
        // Pending native stock deltas may arrive after this packet. Keep the client's stock snapshot so those
        // deltas are applied exactly once; this packet replaces only the sticky (craftable) resource set.
        repository.copyBackingList().copyState().forEach(amount ->
            builder.addResource(amount.resource(), amount.amount()));
        data.autocraftableResources().forEach(builder::addStickyResource);
        repository = builder.build();
        repository.setSort(menu.getSortingType().apply(menu).apply(repository), menu.getSortingDirection());
        repository.setFilterAndSort(createBaseFilter());
        repository.setListener(listener);
        listener.run();
    }
}
