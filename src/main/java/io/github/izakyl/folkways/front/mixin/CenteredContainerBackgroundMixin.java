package io.github.izakyl.folkways.front.mixin;

import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder;
import io.github.izakyl.folkways.front.ui.screen.ColonyShell;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.BeaconScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CrafterScreen;
import net.minecraft.client.gui.screens.inventory.DispenserScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.gui.screens.inventory.GrindstoneScreen;
import net.minecraft.client.gui.screens.inventory.HopperScreen;
import net.minecraft.client.gui.screens.inventory.HorseInventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin({ContainerScreen.class, ShulkerBoxScreen.class, HopperScreen.class, DispenserScreen.class,
    BrewingStandScreen.class, CrafterScreen.class, BeaconScreen.class, GrindstoneScreen.class,
    EnchantmentScreen.class, HorseInventoryScreen.class, MerchantScreen.class})
public abstract class CenteredContainerBackgroundMixin {
    @ModifyVariable(method = "renderBg", at = @At("STORE"), ordinal = 2)
    private int folkways$backgroundLeft(int centered) {
        var screen = (AbstractContainerScreen<?>) (Object) this;
        return screen.getMenu() instanceof IModularUIHolder holder
            && holder.getModularUI() instanceof ColonyShell.SiteUI ? screen.getGuiLeft() : centered;
    }
}
