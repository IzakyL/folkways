package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;

@FunctionalInterface
public interface Windows {

    Pane add(String id, Component title, UIElement body);
}
