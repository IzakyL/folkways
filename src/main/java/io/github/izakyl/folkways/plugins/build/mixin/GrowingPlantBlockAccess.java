package io.github.izakyl.folkways.plugins.build.mixin;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GrowingPlantBlock.class)
public interface GrowingPlantBlockAccess {

    @Accessor("growthDirection")
    Direction folkways$growth();

    @Invoker("getHeadBlock")
    GrowingPlantHeadBlock folkways$head();

    @Invoker("getBodyBlock")
    Block folkways$body();
}
