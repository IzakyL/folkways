package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.api.resource.repository.ResourceRepositoryImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = ResourceRepositoryImpl.class, remap = false)
public interface Rs2RepositoryAccessor {

    @Accessor("listener")
    Runnable folkways$listener();
}
