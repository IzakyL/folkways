package io.github.izakyl.folkways.plugins.supply;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
final class SupplyPanel {
    private static final String KEY = "folkways.supply";
    private final BlockPos at;
    private final long revision;
    private boolean all;
    private List<ItemFilter> filters;
    // A tick, not a button that renames itself: its state reads at a glance, and a click visibly flips it.
    private final Toggle mode = new Toggle();
    private final Button pick = new Button();

    private SupplyPanel(SupplyBox box) {
        at = box.getBlockPos();
        revision = box.revision();
        all = box.allItems();
        filters = box.filters();
    }

    static void open(SupplyBox box) {
        SupplyPanel panel = new SupplyPanel(box);
        LonePanel.open("creative_supply_box", Component.translatable(KEY + ".title"), 340, 170, panel::build);
    }

    private UIElement build(Pane pane) {
        UIElement page = Rows.page();
        mode.setText(Component.translatable(KEY + ".only"));
        mode.setOn(!all, false);
        mode.setOnToggleChanged(only -> {
            all = !only;
            refresh();
        });
        // The box alone answers a click of its own accord; its words should too.
        mode.toggleLabel.addEventListener(UIEvents.MOUSE_DOWN, event -> mode.setOn(!mode.isOn()));
        Tokens.name(mode, KEY + ".mode");
        pick.setOnClick(event -> ItemPicker.many(page, Component.translatable(KEY + ".whitelist"),
            filters, false, next -> { filters = next; refresh(); }));
        Tokens.name(pick, KEY + ".whitelist");
        Button save = new Button().setText(Component.translatable("folkways.action.confirm"))
            .setOnClick(event -> {
                PacketDistributor.sendToServer(new SupplyNetwork.Configure(at, revision, all, filters));
                pane.close();
            });
        Tokens.name(save, KEY + ".save");
        page.addChildren(Rows.name(Component.translatable(KEY + ".help")), mode, pick, Rows.spacer(), Rows.actions(save));
        refresh();
        return page;
    }

    private void refresh() {
        mode.setOn(!all, false);
        mode.style(style -> style.tooltips(Component.translatable(KEY + (all ? ".all" : ".only"))));
        pick.setText(Component.translatable(KEY + ".filters", filters.size()));
        pick.setActive(!all);
    }
}
