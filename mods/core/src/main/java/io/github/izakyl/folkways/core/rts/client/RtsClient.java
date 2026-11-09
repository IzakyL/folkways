package io.github.izakyl.folkways.core.rts.client;

import io.github.izakyl.folkways.core.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.rts.combat.ui.CombatContent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/** Installs local interaction for the server-authoritative RTS services. */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RtsClient {
    private static final CombatController CONTROLLER = new CombatController();

    private RtsClient() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(CombatKeys.MOVE_MODE);
        event.register(CombatKeys.STOP);
        event.register(CombatKeys.RELEASE);
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
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
            CONTROLLER.reset();
        }
    }
}
