package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class LonePanel {

    @FunctionalInterface
    public interface Body {

        UIElement build(Pane pane);
    }

    @FunctionalInterface
    public interface Opener {

        void open(String id, Component title, float width, float height, Body body);
    }

    private static Opener held = (id, title, width, height, body) -> {
        throw new IllegalStateException("lone panels have not been installed");
    };

    private LonePanel() {
    }

    public static void install(Opener opener) {
        held = opener;
    }

    public static void open(String id, Component title, float width, float height, Body body) {
        held.open(id, title, width, height, body);
    }
}
