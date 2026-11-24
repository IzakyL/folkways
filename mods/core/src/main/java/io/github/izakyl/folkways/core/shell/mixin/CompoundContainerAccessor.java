package io.github.izakyl.folkways.core.shell.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessor extends io.github.izakyl.folkways.core.item.api.Containers.Parts {

    @Accessor("container1")
    Container folkways$first();

    @Accessor("container2")
    Container folkways$second();
}
