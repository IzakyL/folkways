package io.github.izakyl.folkways.core.shell.mixin;

import com.jaquadro.minecraft.storagedrawers.block.tile.BlockEntityController;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BlockEntityController.class, remap = false)
public interface DrawerControllerAccessor {

    @Accessor("drawerSlotList")
    List<?> folkways$drawerSlots();
}
