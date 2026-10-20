package io.github.izakyl.folkways.plugins.build.mixin;

import io.github.izakyl.folkways.plugins.build.draft.PatternText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {

    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
        at = @At("HEAD"), cancellable = true, require = 1)
    private void folkways$patternWord(String key, String fallback, CallbackInfoReturnable<String> callback) {
        if (key.startsWith(PatternText.PREFIX)) {
            PatternText.word(Minecraft.getInstance().getLanguageManager().getSelected(), key)
                .ifPresent(callback::setReturnValue);
        }
    }

    @Inject(method = "has", at = @At("HEAD"), cancellable = true, require = 1)
    private void folkways$hasPatternWord(String key, CallbackInfoReturnable<Boolean> callback) {
        if (key.startsWith(PatternText.PREFIX)
            && PatternText.word(Minecraft.getInstance().getLanguageManager().getSelected(), key).isPresent()) {
            callback.setReturnValue(true);
        }
    }
}
