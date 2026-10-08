package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import net.minecraft.resources.ResourceLocation;

public final class Tokens {

    public static final String NONE = "";

    private Tokens() {
    }

    public static void name(UIElement element, String token) {
        if (!token.equals(element.getId())) {
            element.setId(token);
        }
    }

    public static String of(ResourceLocation id) {
        return id.toString().replace(':', '.').replace('/', '.');
    }
}
