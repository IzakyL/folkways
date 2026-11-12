package io.github.izakyl.folkways.core.shell;

import io.github.izakyl.folkways.core.equipment.api.WornGearLayers;
import io.github.izakyl.folkways.core.shell.accessories.AccessoriesLayers;
import io.github.izakyl.folkways.core.shell.curios.CuriosLayers;
import io.github.izakyl.folkways.core.shell.storage.sophisticated.BackpackLayers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/** Hands residents' renderers the worn-gear layers of the frameworks installed, in a player renderer's order. */
@OnlyIn(Dist.CLIENT)
public final class WornGear {
    private WornGear() {
    }

    public static void install() {
        ModList mods = ModList.get();
        if (mods.isLoaded("sophisticatedbackpacks")) {
            WornGearLayers.register(BackpackLayers::worn);
        }
        // With a Curios compatibility layer, Curios items render through Accessories' layer instead.
        if (mods.isLoaded("curios") && !mods.isLoaded("cclayer") && !mods.isLoaded("accessories_compat_layer")) {
            WornGearLayers.register(CuriosLayers::worn);
        }
        if (mods.isLoaded("accessories")) {
            WornGearLayers.register(AccessoriesLayers::worn);
        }
    }
}
