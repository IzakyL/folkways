package io.github.izakyl.folkways.core.shell.mixin;

import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = AccessoriesHolderImpl.class, remap = false)
public interface AccessoriesHolderAccessor {
    @Accessor("slotContainers")
    Map<String, AccessoriesContainer> folkways$containers();
}
