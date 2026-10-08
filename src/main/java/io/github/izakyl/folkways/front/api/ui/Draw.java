package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.List;
import net.minecraft.world.entity.player.Player;

public interface Draw {

    UIElement build(Player player);

    // Figures and page-wide buttons for the right end of the page's title line, asked for after build.
    default List<UIElement> actions() {
        return List.of();
    }

    // Whether the panel heads the page with its name; a page that leads with its own controls says no.
    default boolean titled() {
        return true;
    }

    default void windows(Player player, Windows desk) {
    }
}
