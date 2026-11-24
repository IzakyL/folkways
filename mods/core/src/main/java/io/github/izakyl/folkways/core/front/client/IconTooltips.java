package io.github.izakyl.folkways.core.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, value = Dist.CLIENT)
public final class IconTooltips {

    private static final Map<UIElement, Component> LABELS = new WeakHashMap<>();

    private IconTooltips() {
    }

    public static void install(UIElement element, Component label) {
        element.style(style -> style.tooltips());
        LABELS.put(element, label);
    }

    @SubscribeEvent
    public static void render(ScreenEvent.Render.Post event) {
        ModularUI ui = ModularUI.of(event.getScreen());
        if (ui == null) {
            return;
        }
        for (var entry : LABELS.entrySet()) {
            UIElement element = entry.getKey();
            if (element.getModularUI() != ui || !element.isDisplayed() || !element.isSelfOrChildHover()) {
                continue;
            }
            int x = Math.round(element.getPositionX()) - 12;
            int top = Math.round(element.getPositionY());
            int y = top < event.getScreen().height / 2
                ? Math.round(element.getPositionY() + element.getSizeHeight()) + 24 : top - 24;
            event.getGuiGraphics().renderTooltip(Minecraft.getInstance().font, entry.getValue(), x, y);
            break;
        }
    }
}
