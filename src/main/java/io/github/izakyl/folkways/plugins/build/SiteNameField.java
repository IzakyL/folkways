package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** The row a player names a site in as they hand it over; left empty, the colony numbers it. */
@OnlyIn(Dist.CLIENT)
public final class SiteNameField {

    private static final String TOKEN = "folkways.site_name";

    private final TextField field = new TextField();
    private final UIElement row;

    public SiteNameField() {
        field.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        field.setTextRegexValidator(".{0," + SiteNames.MOST + "}");
        field.textFieldStyle(style -> style.placeholder(Component.translatable(TOKEN + ".unnamed")));
        Tokens.name(field, TOKEN);
        row = Rows.row().addChildren(Rows.name(Component.translatable(TOKEN)), field);
    }

    public UIElement row() {
        return row;
    }

    public String typed() {
        return SiteNames.typed(field.getValue());
    }
}
