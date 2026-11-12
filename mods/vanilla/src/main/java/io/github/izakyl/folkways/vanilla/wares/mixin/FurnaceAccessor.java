package io.github.izakyl.folkways.vanilla.wares.mixin;

import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccessor {

    @Accessor("litTime")
    int folkways$burns();

    @Accessor("cookingProgress")
    int folkways$cooked();

    @Accessor("cookingTotalTime")
    int folkways$cooks();
}
