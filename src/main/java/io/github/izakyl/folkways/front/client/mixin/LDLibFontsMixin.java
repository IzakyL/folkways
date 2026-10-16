package io.github.izakyl.folkways.front.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.lowdragmc.lowdraglib2.gui.LDLibFonts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// LDLib2 centres a line of the 9-tall font in its box, so a box of even height sets the line on a half pixel. Each
// pixel then samples the glyphs on a texel's edge, and the font's top row (or left column) is never drawn: words
// lose the tops of their capitals. Every line LDLib2 draws is set on whole screen pixels instead.
@Mixin(value = LDLibFonts.class, remap = false)
public abstract class LDLibFontsMixin {

    private static final String DRAW = "drawText(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;"
        + "Ljava/lang/Object;Ljava/util/function/Supplier;FFIZ)I";

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private static float folkways$wholeX(float x, @Local(argsOnly = true) GuiGraphics graphics) {
        Matrix4f pose = graphics.pose().last().pose();
        return folkways$whole(x, pose.m00(), pose.m30());
    }

    @ModifyVariable(method = DRAW, at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private static float folkways$wholeY(float y, @Local(argsOnly = true) GuiGraphics graphics) {
        Matrix4f pose = graphics.pose().last().pose();
        return folkways$whole(y, pose.m11(), pose.m31());
    }

    // Where the pose puts a coordinate on the screen, moved to the nearest whole pixel and carried back.
    private static float folkways$whole(float at, float scale, float offset) {
        if (scale == 0f) {
            return at;
        }
        double pixels = Minecraft.getInstance().getWindow().getGuiScale();
        double screen = (scale * at + offset) * pixels;
        return at + (float) ((Math.round(screen) - screen) / pixels / scale);
    }
}
