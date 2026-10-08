package io.github.izakyl.folkways.front.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.front.ui.screen.SelectorDialog;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SelectorWindow {

    private SelectorWindow() {
    }

    public static void open(Screen parent, Consumer<ItemStack> chosen) {
        Minecraft minecraft = Minecraft.getInstance();
        UIElement root = new UIElement()
            .layout(layout -> layout.widthPercent(100).heightPercent(100));
        ModularUI ui = new ModularUI(UI.of(root, StylesheetManager.MC), minecraft.player);
        minecraft.setScreen(new Held(ui, parent));
        SelectorDialog.open(ui, chosen)
            .darkenBackground()
            .setOnClose(() -> minecraft.setScreen(parent));
    }

    private static final class Held extends ModularUIScreen {

        private final Screen parent;

        private Held(ModularUI ui, Screen parent) {
            super(ui, Component.translatable("folkways.selector.title"));
            this.parent = parent;
        }

        @Override
        public void tick() {
            modularUI.tick();
        }

        @Override
        public void onClose() {
            minecraft.setScreen(parent);
        }
    }
}
