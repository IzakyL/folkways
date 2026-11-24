package io.github.izakyl.folkways.assistant.client;

import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.core.front.api.ui.DeskLaunchers;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

@EventBusSubscriber(modid = AssistantMod.ID, value = Dist.CLIENT)
public final class AssistantEvents {

    private AssistantEvents() {
    }

    @SubscribeEvent
    public static void beforeFrame(RenderFrameEvent.Pre event) {
        ScreenshotCapture.beforeFrame(event.getPartialTick());
    }

    @SubscribeEvent
    public static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        ScreenshotCapture.fov(event);
    }

    @SubscribeEvent
    public static void hand(net.neoforged.neoforge.client.event.RenderHandEvent event) {
        if (ScreenshotCapture.capturing()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        while (AssistantClient.OPEN.consumeClick()) {
            if (Minecraft.getInstance().player != null) {
                DeskLaunchers.open(AssistantClient.LAUNCHER);
            }
        }
        AssistantSession.current().tick(Minecraft.getInstance());
        AssistantPanel.checkScreen();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        AssistantPanel.close();
        AssistantSession.disconnect();
        PendingMarks.clear();
        ToolProxy.disconnect();
    }
}
