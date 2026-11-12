package io.github.izakyl.folkways.core.shell.mixin;

import com.tom.storagemod.inventory.PlatformInventoryAccess.BlockInventoryAccess;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = BlockInventoryAccess.class, remap = false)
public interface TomsInventoryAccessor {

    @Accessor("itemCache")
    BlockCapabilityCache<IItemHandler, Direction> folkways$inventory();
}
