package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.LonePanel;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.function.BiConsumer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BlueprintHandoffPanel {

    public static final String TOKEN = "folkways.blueprint.handoff";

    private static final String ID = "blueprint_handoff";

    private static final float WIDTH = 340f;
    private static final float HEIGHT = 128f;

    private final BiConsumer<Boolean, String> handOff;
    private final Button air = new Button();
    private final SiteNameField name = new SiteNameField();

    private boolean excavate;

    private BlueprintHandoffPanel(BiConsumer<Boolean, String> handOff) {
        this.handOff = handOff;
    }

    static void open(BiConsumer<Boolean, String> handOff) {
        BlueprintHandoffPanel panel = new BlueprintHandoffPanel(handOff);
        LonePanel.open(ID, Component.translatable(TOKEN), WIDTH, HEIGHT, panel::build);
    }

    private UIElement build(Pane pane) {
        air.layout(layout -> layout.flexShrink(0));
        air.setOnClick(event -> {
            excavate = !excavate;
            refresh();
        });
        Tokens.name(air, TOKEN + ".air");

        Button confirm = new Button()
            .setText(Component.translatable(TOKEN + ".confirm"))
            .setOnClick(event -> {
                handOff.accept(excavate, name.typed());
                pane.close();
            });
        Tokens.name(confirm, TOKEN + ".confirm");

        refresh();
        UIElement page = Rows.page().addChildren(
            name.row(),
            Rows.row().addChildren(Rows.name(Component.translatable(TOKEN + ".air.heading")), air),
            Rows.spacer(),
            Rows.actions(confirm));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }

    private void refresh() {
        air.setText(Component.translatable(TOKEN + (excavate ? ".air.excavate" : ".air.keep")));
    }
}
