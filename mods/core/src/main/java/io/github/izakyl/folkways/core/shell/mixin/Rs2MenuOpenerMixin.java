package io.github.izakyl.folkways.core.shell.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.izakyl.folkways.core.shell.rs2.TerminalRecipes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "com.refinedmods.refinedstorage.neoforge.support.containermenu.MenuOpenerImpl", remap = false)
public abstract class Rs2MenuOpenerMixin {

    @WrapMethod(method = "openMenu")
    private void folkways$opening(ServerPlayer player, MenuProvider provider, Operation<Void> original) {
        TerminalRecipes.opening(player);
        try {
            original.call(player, provider);
        } finally {
            TerminalRecipes.opening((ServerPlayer) null);
        }
    }
}
