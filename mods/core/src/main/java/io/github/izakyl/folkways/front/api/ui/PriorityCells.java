package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.notice.Notice;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.appliedenergistics.yoga.YogaJustify;

/** Shared vocation headers and priority cells for personal work and arrival defaults. */
public final class PriorityCells {

    public static final int WIDTH = 24;
    private static final int ABBREVIATION = 2;
    private static final int ALLOWED_COLOR = 0xFF2B1C12;
    private static final int BARRED_COLOR = 0xFF8A2416;
    private static final int HEADER_COLOR = 0xFF9A3324;

    private PriorityCells() {
    }

    public static Label of(ResourceLocation trade) {
        Label cell = new Label();
        cell.setText(Component.empty());
        cell.layout(layout -> layout.width(WIDTH - 6).height(16).marginHorizontal(3).flexShrink(0));
        cell.textStyle(style -> style.textAlignHorizontal(Horizontal.CENTER)
            .textAlignVertical(Vertical.CENTER).textWrap(TextWrap.HIDE));
        cell.addClass("folkways_priority");
        Cues.under(cell, vocationName(trade),
            Cue.of("folkways.keys.allow", Cue.Key.CLICK), Cue.of("folkways.keys.rank", Cue.Key.WHEEL));
        // The wheel ranks the cell; it must not also scroll the table beneath it.
        cell.addEventListener(UIEvents.MOUSE_WHEEL, UIEvent::stopPropagation);
        return cell;
    }

    public static void show(Label cell, int standing) {
        cell.setText(Component.literal(cell(standing)));
        cell.textStyle(style -> style.textColor(standing < 0 ? BARRED_COLOR : ALLOWED_COLOR));
    }

    /** A vocation's column is headed by its glyph, or the first letters of its name where it has none. */
    public static UIElement header(boolean client) {
        UIElement cells = row();
        for (ResourceLocation trade : Vocations.ids()) {
            Component full = vocationName(trade);
            UIElement glyph = SignLine.of();
            UIElement cell = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .justifyContent(YogaJustify.CENTER).alignItems(AlignItems.CENTER)
                .width(WIDTH).height(Rows.ROW_HEIGHT).flexShrink(0));
            cell.addChildren(glyph);
            cell.style(style -> style.tooltips(full));
            if (client) {
                SignLine.show(glyph, Sentence.of(Sentence.glyph(
                    ResourceLocation.fromNamespaceAndPath(trade.getNamespace(), "vocation/" + trade.getPath()),
                    new Notice("folkways.sign.as_is", List.of(Notice.text(abbreviate(full)))))), HEADER_COLOR);
            }
            cells.addChildren(cell);
        }
        return cells;
    }

    public static UIElement row() {
        return new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .alignItems(AlignItems.CENTER).flexShrink(0).gapAll(0));
    }

    private static Component vocationName(ResourceLocation trade) {
        return Component.translatable(
            "folkways.vocation." + trade.getNamespace() + "." + trade.getPath());
    }

    private static String abbreviate(Component name) {
        String written = name.getString();
        return written.length() <= ABBREVIATION ? written : written.substring(0, ABBREVIATION);
    }

    private static String cell(int standing) {
        if (standing == 0) {
            return "";
        }
        return standing > 0 ? Integer.toString(standing) : "–";
    }
}
