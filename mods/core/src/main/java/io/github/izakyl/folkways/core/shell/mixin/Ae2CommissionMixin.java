package io.github.izakyl.folkways.core.shell.mixin;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.energy.IEnergyService;
import appeng.me.service.CraftingService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.izakyl.folkways.core.shell.crafting.NetworkTask;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "appeng.crafting.execution.CraftingCpuLogic", remap = false)
public abstract class Ae2CommissionMixin {

    @Shadow
    public abstract ICraftingLink getLastLink();

    @WrapMethod(method = "executeCrafting")
    private int folkways$commission(int quota, CraftingService crafting, IEnergyService energy, Level level,
                                    Operation<Integer> original) {
        ICraftingLink link = getLastLink();
        try (var scope = new NetworkTask(link == null ? null : link.getCraftingID())) {
            return original.call(quota, crafting, energy, level);
        }
    }
}
