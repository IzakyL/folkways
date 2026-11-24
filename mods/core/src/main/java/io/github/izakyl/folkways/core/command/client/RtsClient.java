package io.github.izakyl.folkways.core.command.client;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.command.combat.net.ClientUnits;
import io.github.izakyl.folkways.core.command.combat.ui.CombatContent;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Installs local interaction for the server-authoritative RTS services. */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RtsClient {
    private static final CombatController CONTROLLER = new CombatController();

    private RtsClient() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        CombatKeys.ALL.forEach(event::register);
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> OverheadTools.registerHandler(CombatContent.ID, CONTROLLER));
    }

    @EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class Events {
        private Events() {
        }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) {
            CONTROLLER.tick();
        }

        @SubscribeEvent
        public static void screenInit(ScreenEvent.Init.Post event) {
            if (Overhead.activeScreen() && Minecraft.getInstance().screen == event.getScreen()) {
                CONTROLLER.window().attach(event.getScreen(), event::addListener);
            }
        }

        // The unit window takes presses that land on it before the overhead screen hands them to the RTS tool.
        @SubscribeEvent
        public static void mousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
            if (CONTROLLER.window().attachedTo(event.getScreen())
                && CONTROLLER.window().pressed(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void mouseDragged(ScreenEvent.MouseDragged.Pre event) {
            if (CONTROLLER.window().attachedTo(event.getScreen()) && CONTROLLER.window().dragged(event.getMouseX(),
                event.getMouseY(), event.getMouseButton(), event.getDragX(), event.getDragY())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void mouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
            if (CONTROLLER.window().attachedTo(event.getScreen())
                && CONTROLLER.window().released(event.getMouseX(), event.getMouseY(), event.getButton())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void mouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
            if (CONTROLLER.window().attachedTo(event.getScreen()) && CONTROLLER.window().scrolled(event.getMouseX(),
                event.getMouseY(), event.getScrollDeltaX(), event.getScrollDeltaY())) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent
        public static void unload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
            if (event.getLevel().isClientSide()) {
                CONTROLLER.reset();
                ClientUnits.clear();
            }
        }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            CONTROLLER.reset();
            ClientUnits.clear();
        }
    }
}
