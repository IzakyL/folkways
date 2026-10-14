package io.github.izakyl.folkways.front.client.ponder.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import io.github.izakyl.folkways.FolkwaysMod;
import net.createmod.ponder.foundation.PonderScene;
import net.createmod.ponder.foundation.element.TextWindowElement;
import net.createmod.ponder.foundation.ui.PonderUI;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

// Ponder hangs a lesson's text from the point it shows, downward: on a short screen, a point high in the scene put
// the box above the top edge and a long text ran off the bottom. A lesson of ours keeps its box on the screen and
// clear of the row of buttons along its foot.
@Pseudo
@Mixin(value = TextWindowElement.class, remap = false)
public abstract class TextWindowElementMixin {

    // Ponder never wraps a text narrower than this share of the screen, and never wider than its widest.
    private static final float NARROWEST_SHARE = 0.25F;
    private static final int WIDEST = 180;
    private static final int MARGIN = 4;
    // Ponder's buttons stand this far up from the foot of the screen.
    private static final int BUTTONS = 51;

    @Shadow
    String bakedText;

    @ModifyArg(method = "render",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(FFF)V", ordinal = 0),
        index = 1)
    private float folkways$onScreen(float top, @Local(argsOnly = true) PonderScene scene,
            @Local(argsOnly = true) PonderUI screen) {
        if (bakedText == null || !FolkwaysMod.MOD_ID.equals(scene.getNamespace())) {
            return top;
        }
        int narrowest = (int) Math.min(screen.width * NARROWEST_SHARE, WIDEST);
        int tallest = screen.getFontRenderer().wordWrapHeight(bakedText, Math.max(1, narrowest));
        float lowest = screen.height - BUTTONS - tallest - 2.0F * MARGIN;
        return lowest < MARGIN ? MARGIN : Mth.clamp(top, MARGIN, lowest);
    }
}
