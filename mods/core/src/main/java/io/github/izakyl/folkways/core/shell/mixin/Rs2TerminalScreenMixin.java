package io.github.izakyl.folkways.core.shell.mixin;

import com.refinedmods.refinedstorage.common.grid.screen.AbstractGridScreen;
import com.refinedmods.refinedstorage.common.support.AbstractBaseScreen;
import com.refinedmods.refinedstorage.common.support.Sprites;
import com.refinedmods.refinedstorage.common.support.widget.AbstractSideButtonWidget;
import io.github.izakyl.folkways.core.shell.crafting.TerminalPreferences;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractGridScreen.class, remap = false)
public abstract class Rs2TerminalScreenMixin {

    @Inject(method = "init(I)V", at = @At("TAIL"))
    private void folkways$button(int rows, CallbackInfo callback) {
        ((AbstractBaseScreen<?>) (Object) this).addSideButton(new AbstractSideButtonWidget(
            button -> TerminalPreferences.toggle()) {
            @Override
            protected ResourceLocation getSprite() {
                return Sprites.AUTOCRAFTING_INDICATOR;
            }

            @Override
            protected MutableComponent getTitle() {
                return Component.translatable("folkways.network.recipes");
            }

            @Override
            protected List<MutableComponent> getSubText() {
                // Like RS2's own side buttons: the setting's title, then its current value in grey.
                return List.of(Component.translatable(TerminalPreferences.visible()
                    ? "folkways.network.recipes.shown" : "folkways.network.recipes.hidden")
                    .withStyle(ChatFormatting.GRAY));
            }
        });
        TerminalPreferences.request();
    }
}
