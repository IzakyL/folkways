package io.github.izakyl.folkways.assistant.client;

import io.github.izakyl.folkways.assistant.AssistantMod;
import io.github.izakyl.folkways.core.front.api.ui.DeskLaunchers;
import io.github.izakyl.folkways.assistant.net.ToolNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = AssistantMod.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AssistantClient {

    static final String LAUNCHER = "folkways.assistant.launcher";

    static final KeyMapping OPEN = new KeyMapping("key.folkways.assistant", GLFW.GLFW_KEY_UNKNOWN,
            "key.categories.folkways");

    private AssistantClient() {
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            if (System.getProperty("jpimini.gameDir") == null) {
                System.setProperty("jpimini.gameDir", Minecraft.getInstance().gameDirectory.getAbsolutePath());
            }
            ToolNetwork.receivers(ToolProxy::receive, ToolProxy::receive);
            ToolNetwork.marks(CalibrationMarks::receive);
            DeskLaunchers.register(new DeskLaunchers.Launcher(LAUNCHER,
                    ResourceLocation.fromNamespaceAndPath("folkways_assistant", "textures/gui/assistant/launcher.png"),
                    "folkways.assistant.title", AssistantPanel::open));
            AskOffer.register();
        });
    }
}
