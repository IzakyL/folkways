package io.github.izakyl.folkways.plugins.person.mixin;

import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FontManager.class)
public abstract class FontManagerMixin {

    @Shadow
    private volatile FontSet lastFontSetCache;

    @Shadow
    protected abstract FontSet getFontSetRaw(ResourceLocation id);

    @Inject(method = "getFontSetCached", at = @At("RETURN"), cancellable = true, require = 1)
    private void folkways$currentFontSet(ResourceLocation id, CallbackInfoReturnable<FontSet> callback) {
        FontSet current = getFontSetRaw(id);
        if (callback.getReturnValue() != current) {
            lastFontSetCache = current;
            callback.setReturnValue(current);
        }
    }
}
