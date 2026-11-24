package io.github.izakyl.folkways.core.front.client;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.front.api.Category;
import io.github.izakyl.folkways.core.front.api.Shape;
import io.github.izakyl.folkways.core.front.api.ui.LonePanel;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * What a whole shape drawn in the player's own eyes asks once the book is used on it: what it should become. The
 * kinds and offers drawn its way are listed under their categories; one picked becomes a holding whose card opens,
 * or opens what the offer asks. Backing out keeps the shape, to be handed over again or let go.
 */
@OnlyIn(Dist.CLIENT)
final class ShapePicker {
    static final String ID = "folkways_book_pick";
    static final String TOKEN = "folkways.book.pick";
    private static final float WIDTH = 220f;
    private static final float HEIGHT = 300f;
    private static final int OFF = 0xFF8F7F62;

    private ShapePicker() {
    }

    static void open(Shape.Gesture gesture) {
        LonePanel.open(ID, Component.translatable(TOKEN + ".title"), WIDTH, HEIGHT, pane -> body(pane, gesture));
    }

    /** The element a candidate's row is named by, for whoever reads the screen. */
    static String token(BookTools.Entry entry) {
        return TOKEN + "." + Tokens.of(switch (entry) {
            case BookTools.Kind kind -> kind.delegation().id();
            case BookTools.Offer offer -> offer.id();
            case BookTools.Pointing ignored -> ResourceLocation.fromNamespaceAndPath("folkways_core", BookTools.POINT);
        });
    }

    private static UIElement body(Pane pane, Shape.Gesture gesture) {
        UIElement page = Rows.page();
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        BookTools.measure().ifPresent(size -> page.addChildren(Rows.note(size)));
        Map<Category, List<BookTools.Entry>> candidates = BookTools.candidates(gesture);
        if (candidates.isEmpty()) {
            return page.addChildren(Rows.note(Component.translatable("folkways.zoneconfig.no_types")));
        }
        UIElement list = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        candidates.forEach((category, entries) -> {
            list.addChildren(Rows.group(Component.translatable(category.nameKey())));
            entries.forEach(entry -> list.addChildren(row(pane, entry)));
        });
        ScrollerView rows = Rows.fills(Rows.box());
        rows.addScrollViewChild(list);
        Button discard = new Button().setText(Component.translatable("folkways.keys.discard"))
            .setOnClick(event -> {
                BookTools.drop();
                pane.close();
            });
        discard.layout(layout -> layout.flexShrink(0));
        Tokens.name(discard, TOKEN + ".discard");
        // The shape may be let go of meanwhile, from another window or a key; there is then nothing to pick for.
        page.addEventListener(UIEvents.TICK, event -> {
            if (!BookTools.ready() && pane.isOpen()) {
                pane.close();
            }
        });
        return page.addChildren(rows, Rows.actions(discard));
    }

    private static UIElement row(Pane pane, BookTools.Entry entry) {
        Button row = new Button().noText();
        Tokens.name(row, token(entry));
        row.layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(Rows.ROW_HEIGHT).flexShrink(0)
            .alignItems(AlignItems.CENTER).gapAll(Rows.GAP - 1).paddingAll(1));
        row.addClass("folkways_nav");
        row.setOverflowVisible(false);
        Label name = Rows.name(entry.name());
        row.addChildren(Rows.icon(entry.icon()), name);
        Optional<Component> refused = BookTools.refusal(entry);
        if (refused.isPresent()) {
            row.setActive(false);
            name.textStyle(style -> style.textColor(OFF));
            row.style(style -> style.tooltips(refused.get()));
        } else {
            row.style(style -> style.tooltips(entry.how()));
            row.setOnClick(event -> {
                // The picker gives way first, so a card or an offer opened next goes back to what it was opened over.
                pane.close();
                BookTools.handTo(entry.key());
            });
        }
        return row;
    }
}
