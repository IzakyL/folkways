package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.facility.api.Ports;
import io.github.izakyl.folkways.core.resident.api.KitProviders;
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
        Ports.registerItem(ResourceLocation.fromNamespaceAndPath("folkways_core", "sophisticated_backpacks"),
            Ports.SPECIFIC, (level, pos) -> level.getBlockEntity(pos) instanceof BackpackBlockEntity backpack
                ? Optional.of(SophisticatedPort.create(level, pos, backpack.getBackpackWrapper(), () -> false))
                : Optional.empty());
    }
}
