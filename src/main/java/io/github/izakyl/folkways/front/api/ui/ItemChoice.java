package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ItemChoice {

    @FunctionalInterface
    public interface Opener {

        void open(Screen parent, Consumer<Optional<ItemFilter>> chosen);
    }

    private static Opener held = (parent, chosen) -> {
    };

    private ItemChoice() {
    }

    public static void install(Opener opener) {
        held = opener;
    }

    public static void open(Screen parent, Consumer<Optional<ItemFilter>> chosen) {
        held.open(parent, chosen);
    }
}
