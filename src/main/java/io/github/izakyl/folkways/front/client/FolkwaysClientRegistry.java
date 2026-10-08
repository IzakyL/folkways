package io.github.izakyl.folkways.front.client;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.front.client.ponder.PonderIntegration;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.ItemChoice;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.front.ui.screen.LonePanels;
import io.github.izakyl.folkways.front.ui.screen.Window;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FolkwaysClientRegistry {
    private FolkwaysClientRegistry() {
    }

    @SubscribeEvent
    public static void registerPonder(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            PonderIntegration.install();
            LonePanel.install(LonePanels::open);
            ChoicePicker.install(Window::aboveWindows);
            ItemChoice.install((parent, chosen) ->
                SelectorWindow.open(parent, stack -> chosen.accept(FilterStacks.parse(stack))));
            ItemProperties.register(FolkwaysItems.COLONY_BOOK.get(),
                ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "mode"),
                (stack, level, entity, seed) -> switch (ColonyBookItem.gesture(stack)) {
                    case POINT -> 0.0F;
                    case BOX -> 1.0F;
                    case LINE -> 2.0F;
                });
        });
    }
}
