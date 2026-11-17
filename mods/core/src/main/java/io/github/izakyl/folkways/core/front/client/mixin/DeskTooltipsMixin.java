package io.github.izakyl.folkways.core.front.client.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import io.github.izakyl.folkways.core.front.ui.screen.DeskTextures;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GuiGraphics.class)
public abstract class DeskTooltipsMixin {

    @WrapMethod(method = "renderTooltipInternal")
    private void folkways$aboveWindows(Font font, List<ClientTooltipComponent> components, int x, int y,
            ClientTooltipPositioner positioner, Operation<Void> original) {
        if (!DeskTextures.isDesk(ModularUI.of(Minecraft.getInstance().screen))) {
            original.call(font, components, x, y, positioner);
            return;
        }
        GuiGraphics graphics = (GuiGraphics) (Object) this;
        graphics.pose().pushPose();
        // LDLib2 adds window zIndex values to the pose, but its final tooltip pass only adds a fixed 200.
        // Both that pass and Ponder's screen-post prompt reach this vanilla entry point (which adds another 400).
        graphics.pose().translate(0f, 0f, Window.aboveWindows());
        try {
            original.call(font, components, x, y, positioner);
        } finally {
            graphics.pose().popPose();
        }
    }
}
