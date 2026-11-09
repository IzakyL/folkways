package io.github.izakyl.folkways.core.front.client;

import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import io.github.izakyl.folkways.core.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.core.front.ui.screen.DeskTextures;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class SelectorWindow {

    private SelectorWindow() {
    }

    public static void open(Screen parent, Consumer<ItemStack> chosen) {
        open(parent, Component.translatable("folkways.selector.title"), root ->
            ItemPicker.one(root, Component.translatable("folkways.selector.title"), null, false,
                id -> chosen.accept(new ItemStack(BuiltInRegistries.ITEM.get(id)))));
    }

    public static void open(Screen parent, Component title,
            java.util.function.Function<UIElement, com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog> dialog) {
        Minecraft minecraft = Minecraft.getInstance();
        UIElement root = new UIElement()
            .layout(layout -> layout.widthPercent(100).heightPercent(100)).addClass("folkways_desk");
        DeskTextures.installPopups(root);
        ModularUI ui = new ModularUI(UI.of(root,
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    ResourceLocation.fromNamespaceAndPath("folkways_core", "lss/desk.lss")))), minecraft.player);
        minecraft.setScreen(new Held(ui, parent, title));
        dialog.apply(root).darkenBackground().setOnClose(() -> minecraft.setScreen(parent));
    }

    private static final class Held extends ModularUIScreen {

        private final Screen parent;

        private Held(ModularUI ui, Screen parent, Component title) {
            super(ui, title);
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
