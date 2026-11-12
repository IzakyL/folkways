package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.resident.api.KitProviders;
import io.github.izakyl.folkways.core.inventory.api.port.StorePorts;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;

public final class BackpacksIntegration {

    private BackpacksIntegration() {
    }

    public static void install() {
        ResidentBackpacks.install();
        ResidentJukebox.install();
        KitProviders.register(
            ResourceLocation.fromNamespaceAndPath("folkways_core", "sophisticated_backpacks"), new BackpackKits());
        SophisticatedWorkstations.install("sophisticated_backpacks", (level, pos) ->
            level.getBlockEntity(pos) instanceof BackpackBlockEntity storage
                ? Optional.of(storage.getBackpackWrapper()) : Optional.empty());
        StorePorts.register(ResourceLocation.fromNamespaceAndPath("folkways_core", "sophisticated_backpacks"),
            StorePorts.SPECIFIC, (level, pos) -> level.getBlockEntity(pos) instanceof BackpackBlockEntity backpack
                ? Optional.of(SophisticatedPort.create(level, pos, backpack.getBackpackWrapper(), () -> false))
                : Optional.empty());
    }
}
