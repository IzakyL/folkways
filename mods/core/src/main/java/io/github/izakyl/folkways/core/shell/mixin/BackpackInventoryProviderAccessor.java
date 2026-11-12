package io.github.izakyl.folkways.core.shell.mixin;

import java.util.Map;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryHandler;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Preserve the native registration priority instead of imposing the equipment page's display order. */
@Mixin(value = PlayerInventoryProvider.class, remap = false)
public interface BackpackInventoryProviderAccessor {
    @Accessor("playerInventoryHandlers")
    Map<String, PlayerInventoryHandler> folkways$handlers();
}
