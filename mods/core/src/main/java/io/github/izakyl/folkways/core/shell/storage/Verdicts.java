package io.github.izakyl.folkways.core.shell.storage;

import io.github.izakyl.folkways.core.inventory.api.port.Usability;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public final class Verdicts {

    private Verdicts() {
    }

    public static Usability upgrade(ItemStack upgrade) {
        return Usability.because(Component.translatable("folkways.storage.unusable.upgrade", upgrade.getHoverName()));
    }

    public static Usability because(String reason) {
        return Usability.because(Component.translatable("folkways.storage.unusable." + reason));
    }
}
