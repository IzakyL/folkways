package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
// A single panel over the game, opened from outside the colony panel and back to the screen it left.
public final class LonePanels {

    private static final float MIN_WIDTH = 160f;
    private static final float MIN_HEIGHT = 80f;

    private LonePanels() {
    }

    public static void open(String id, Component title, float width, float height, LonePanel.Body body) {
        Minecraft minecraft = Minecraft.getInstance();
        Screen parent = minecraft.screen;
        Desk desk = new Desk();
        Window window = desk.panel(
            Window.of(id, title, 0f, 0f, width, height, MIN_WIDTH, MIN_HEIGHT));
        window.body().addChildren(body.build(window));
        window.setOnClose(() -> back(parent));
        window.onDismiss(() -> back(parent));
        ModularUI ui = new ModularUI(UI.of(desk.root(),
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "lss/desk.lss"))),
            screen -> screen), minecraft.player);
        minecraft.setScreen(new Held(ui, title, parent));
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

        private Held(ModularUI ui, Component title, Screen parent) {
            super(ui, title);
            this.parent = parent;
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
            minecraft.setScreen(parent);
        }
    }
}
