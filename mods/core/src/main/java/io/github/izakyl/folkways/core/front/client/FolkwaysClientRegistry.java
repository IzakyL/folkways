package io.github.izakyl.folkways.core.front.client;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.client.ponder.PonderIntegration;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Cues;
import io.github.izakyl.folkways.core.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.core.front.api.ui.ItemChoice;
import io.github.izakyl.folkways.core.front.api.ui.LonePanel;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Placing;
import io.github.izakyl.folkways.core.front.client.ponder.PonderIntegration;
import io.github.izakyl.folkways.core.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.core.front.engine.registry.FolkwaysItems;
import io.github.izakyl.folkways.core.front.ui.screen.LonePanels;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FolkwaysClientRegistry {
    private FolkwaysClientRegistry() {
    }

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(Overhead.KEY);
        event.register(PersonalCameraKeyEvents.KEY);
    }

    @SubscribeEvent
    public static void registerTooltips(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(Cues.class, CueLines::new);
        event.register(LedgerTooltips.Line.class, LedgerTooltips::lines);
        event.register(LedgerTooltips.Inked.class, inked -> new CueLines(inked.cues(), true));
    }

    @SubscribeEvent
    public static void registerPonder(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ModList.get().getModContainerById(FolkwaysCoreMod.CORE_ID).ifPresent(container ->
                container.registerExtensionPoint(IConfigScreenFactory.class, PersonalCameraConfigScreen::open));
            if (ModList.get().isLoaded("jecharacters")) {
                JechSearch.install();
            }
            PonderIntegration.install();
            Pages.installOpening(BookPages::open);
            LonePanel.install(LonePanels::open);
            Placing.install(HeldPlacement.HAND);
            SweepOffer.register();
            Picker.install(Window::aboveWindows);
            ItemChoice.install((parent, chosen) ->
                SelectorWindow.open(parent, stack -> chosen.accept(FilterStacks.parse(stack))));
            ItemProperties.register(FolkwaysItems.COLONY_BOOK.get(),
                ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "mode"),
                (stack, level, entity, seed) -> switch (ColonyBookItem.gesture(stack)) {
                    case POINT -> 0.0F;
                    case BOX -> 1.0F;
                    case LINE -> 2.0F;
                });
        });
    }
}
