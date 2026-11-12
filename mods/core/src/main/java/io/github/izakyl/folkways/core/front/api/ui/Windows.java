package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;

@FunctionalInterface
public interface Windows {
    float WIDTH = 300, HEIGHT = 230;

    /** A window opened at {@code width} by {@code height} until the player resizes it. */
    Pane add(String id, Component title, UIElement body, float width, float height);

    default Pane add(String id, Component title, UIElement body) {
        return add(id, title, body, WIDTH, HEIGHT);
    }
}
