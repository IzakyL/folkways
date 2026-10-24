package io.github.izakyl.folkways.cookery.tavern;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** The subscriber itself has no Tavern API references and remains safe without the optional jar. */
@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
public final class TavernGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        if (ModList.get().isLoaded("kaleidoscope_tavern")) event.register(TavernNativeGameTests.class);
    }
}
