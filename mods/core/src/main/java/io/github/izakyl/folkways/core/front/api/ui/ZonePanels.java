package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

public final class ZonePanels {

    private record Panel(List<ResourceLocation> delegations, BiFunction<Player, Supplier<UUID>, UIElement> factory) {
    }

    private static final List<Panel> PANELS = new ArrayList<>();

    private ZonePanels() {
    }

    static void register(List<ResourceLocation> delegations, BiFunction<Player, Supplier<UUID>, UIElement> factory) {
        PANELS.add(new Panel(List.copyOf(delegations), factory));
    }

    public static boolean has(ResourceLocation delegation) {
        return PANELS.stream().anyMatch(panel -> panel.delegations().contains(delegation));
    }

    public static UIElement build(Player player, Supplier<UUID> selected) {
        UIElement panels = Rows.table();
        for (Panel panel : PANELS) {
            panels.addChildren(panel.factory().apply(player, selected));
        }
        return panels;
    }
}
