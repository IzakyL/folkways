package io.github.izakyl.folkways.core.shell.cookery.mixin;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import io.github.izakyl.folkways.core.shell.cookery.api.Appliances;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ChoppingBoardBlockEntity.class)
public abstract class ChoppingBoardInputMixin implements Appliances.BoardInput {

    @Shadow(remap = false)
    private List<ItemStack> results;

    @Override
    public boolean folkways$inputFree() {
        return results.isEmpty();
    }
}
