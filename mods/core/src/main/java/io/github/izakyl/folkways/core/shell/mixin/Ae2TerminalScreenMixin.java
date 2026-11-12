package io.github.izakyl.folkways.core.shell.mixin;

import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.me.common.MEStorageScreen;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ToggleButton;
import appeng.menu.me.common.MEStorageMenu;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MEStorageScreen.class, remap = false)
public abstract class Ae2TerminalScreenMixin extends AEBaseScreen<MEStorageMenu> {

    @Unique
    private ToggleButton folkways$recipes;

    protected Ae2TerminalScreenMixin(MEStorageMenu menu, Inventory inventory, Component title, ScreenStyle style) {
        super(menu, inventory, title, style);
    }

    // AE2 registers toolbar buttons with the screen during init, so they must exist by the end of the constructor.
    @Inject(method = "<init>", at = @At("TAIL"))
    private void folkways$button(MEStorageMenu menu, Inventory inventory, Component title, ScreenStyle style,
                                 CallbackInfo callback) {
        folkways$recipes = addToLeftToolbar(new ToggleButton(Icon.PATTERN_ACCESS_SHOW, Icon.PATTERN_ACCESS_HIDE,
            Component.translatable("folkways.network.recipes"),
            Component.translatable("folkways.network.recipes.hint"), button -> TerminalPreferences.toggle()));
        folkways$recipes.setState(TerminalPreferences.visible());
        TerminalPreferences.request();
    }

    @Inject(method = "updateBeforeRender", at = @At("TAIL"))
    private void folkways$state(CallbackInfo callback) {
        if (folkways$recipes != null) {
            folkways$recipes.setState(TerminalPreferences.visible());
        }
    }
}
