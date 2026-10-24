package io.github.izakyl.folkways.war.client;

import io.github.izakyl.folkways.front.api.ui.OverheadTools;
import io.github.izakyl.folkways.plugins.combat.ui.CombatContent;
import io.github.izakyl.folkways.war.FolkwaysWarMod;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Installs only local interaction; Vanilla still owns every resident and actual order. */
@EventBusSubscriber(modid = FolkwaysWarMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class WarClient {
    private static final CombatController CONTROLLER = new CombatController();

    private WarClient() { }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> OverheadTools.registerHandler(CombatContent.ID, CONTROLLER));
    }

    @EventBusSubscriber(modid = FolkwaysWarMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
    public static final class Events {
        private Events() { }

        @SubscribeEvent
        public static void tick(ClientTickEvent.Post event) { CONTROLLER.tick(); }

        @SubscribeEvent
        public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { CONTROLLER.reset(); }
    }
}
