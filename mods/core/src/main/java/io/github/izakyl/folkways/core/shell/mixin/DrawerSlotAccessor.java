package io.github.izakyl.folkways.core.shell.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityController$SlotRecord", remap = false)
public interface DrawerSlotAccessor {

    @Accessor("coord")
    BlockPos folkways$position();
}
