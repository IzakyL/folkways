package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.HoverTooltips;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;

/** The key hints of a tooltip, drawn under its heading, or as the whole of it when it has none. */
public record Cues(List<Cue> lines, boolean headed) implements TooltipComponent {

    public Cues {
        lines = List.copyOf(lines);
    }

    public static void on(UIElement element, Cue... cues) {
        tooltip(element, Component.empty(), new Cues(List.of(cues), false));
    }

    public static void under(UIElement element, Component heading, Cue... cues) {
        tooltip(element, heading, new Cues(List.of(cues), true));
    }

    private static void tooltip(UIElement element, Component heading, Cues cues) {
        element.addEventListener(UIEvents.HOVER_TOOLTIPS,
            event -> event.hoverTooltips = HoverTooltips.empty().append(heading).tooltipComponent(cues));
    }
}
