package io.github.izakyl.folkways.core.shell.storage.sophisticated;

import io.github.izakyl.folkways.core.inventory.api.StoreWorkstations;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;

final class SophisticatedWorkstations {

    private SophisticatedWorkstations() {
    }

    static void install(String name, BiFunction<ServerLevel, BlockPos, Optional<IStorageWrapper>> wrappers) {
        StoreWorkstations.register(ResourceLocation.fromNamespaceAndPath("folkways_core", name),
            (level, pos) -> wrappers.apply(level, pos).map(SophisticatedWorkstations::capabilities).orElse(Set.of()));
    }

    private static Set<StoreWorkstations.Capability> capabilities(IStorageWrapper wrapper) {
        if (!SophisticatedPort.usability(wrapper).usable()) {
            return Set.of();
        }
        Set<StoreWorkstations.Capability> found = EnumSet.noneOf(StoreWorkstations.Capability.class);
        // Native effective wrappers include Inception's inner upgrades only when they are functional.
        for (IUpgradeWrapper effective : wrapper.getUpgradeHandler().getWrappersThatImplement(IUpgradeWrapper.class)) {
            switch (BuiltInRegistries.ITEM.getKey(effective.getUpgradeStack().getItem()).getPath()) {
                case "crafting_upgrade" -> found.add(StoreWorkstations.Capability.CRAFTING);
                case "stonecutter_upgrade" -> found.add(StoreWorkstations.Capability.STONECUTTING);
                case "smithing_upgrade" -> found.add(StoreWorkstations.Capability.SMITHING);
                default -> { }
            }
        }
        return Set.copyOf(found);
    }
}
