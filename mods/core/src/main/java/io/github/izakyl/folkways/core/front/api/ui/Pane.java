package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

public interface Pane {

    void open();

    void close();

    boolean isOpen();

    void title(Component text);

    void onDismiss(Runnable both);

    void titleAction(UIElement action);

    void onClose(Runnable closed);

    void beside(Pane pane);

    void offsetCloseTooltip();

    /**
     * Dresses the window apart from the desk: its frame wears the background in place of paper and oak, takes the
     * style class, and carries the stylesheet down to everything inside it, title bar included.
     */
    void dress(String styleClass, ResourceLocation stylesheet, IGuiTexture background);
}
