package io.github.izakyl.folkways.core.card.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.client.Overhead;
import io.github.izakyl.folkways.core.front.ui.screen.Desk;
import io.github.izakyl.folkways.core.front.ui.screen.Window;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * A facility's card in a window of its own over the game, the facility's name in its caption, opened when the server
 * asks ({@code CardOpen}) or from another card. While it is open the client watches the card, so the server keeps its
 * status coming; it closes back to the screen it was opened over. Over the view from above it closes to no screen at
 * all and lets the view put its own back, as the book's panel does, so one Escape closes the card and only the next
 * one leaves the view.
 */
@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CardWindow {
    static final String ID = "folkways_card";
    private static final float WIDTH = 340;
    private static final float HEIGHT = 320;

    private CardWindow() {
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> ClientCards.opener(open -> open(open.colony(), open.facility())));
    }

    /** Opens the facility's card over whatever screen is showing. */
    public static void open(UUID colony, UUID facility) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        // The view from above restores its screen itself once nothing is open; another screen is put back here.
        Screen parent = Overhead.activeScreen() ? null : minecraft.screen;
        Desk desk = new Desk();
        Window window = desk.panel(Window.of(ID, Component.translatable(CardView.ID + "window"), 0f, 0f, WIDTH,
            HEIGHT, 180f, 100f));
        Tokens.name(window.frame(), CardView.ID + "window");
        CardPane shown = new CardPane(window, desk.root());
        shown.show(colony, facility);
        window.setOnClose(() -> back(parent));
        window.onDismiss(() -> back(parent));
        desk.root().addEventListener(UIEvents.TICK, event -> shown.refresh());
        ModularUI ui = new ModularUI(UI.of(desk.root(), List.of(
            StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
            StylesheetManager.INSTANCE.getStylesheetSafe(
                ResourceLocation.fromNamespaceAndPath("folkways_core", "lss/desk.lss"))), screen -> screen),
            minecraft.player);
        minecraft.setScreen(new Held(ui, parent, colony, facility));
        window.open();
    }

    private static void back(Screen parent) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen instanceof Held) {
            minecraft.setScreen(parent);
        }
    }

    private static final class Held extends ModularUIScreen {
        private final Screen parent;
        private final UUID colony;
        private final UUID facility;

        private Held(ModularUI ui, Screen parent, UUID colony, UUID facility) {
            super(ui, Component.translatable(CardView.ID + "window"));
            this.parent = parent;
            this.colony = colony;
            this.facility = facility;
        }

        @Override
        public void init() {
            super.init();
            ClientCards.watch(colony, facility, true);
        }

        @Override
        public void removed() {
            super.removed();
            ClientCards.watch(colony, facility, false);
        }

        @Override
        public void tick() {
            modularUI.tick();
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }

        @Override
        public void onClose() {
            back(parent);
        }
    }
}
