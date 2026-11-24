package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Editor;
import io.github.izakyl.folkways.core.front.api.ui.Pane;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.api.ui.Windows;
import io.github.izakyl.folkways.core.front.ui.UiRegistrations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * A core page with the modules' sections of it ({@link ColonyPanel}) above its own content, while they have rows,
 * and a button for each editor registered for the page, each opening the editor in a window of the book. A page that
 * fits the book and scrolls its own content keeps the editors and sections in a band above it; a flowing page is laid
 * at its full height under them, and the whole page scrolls as one.
 */
final class Sectioned implements Draw {
    // The most of the page the editors and sections take before they scroll.
    private static final float BAND = 0.45f;
    private final Draw page;
    private final ColonyPanel.Page sections;
    private final List<Editor> editors;
    private final boolean flowing;
    private final Map<ResourceLocation, Pane> opened = new LinkedHashMap<>();
    private Windows desk;

    Sectioned(Draw page, ColonyPanel.Page sections) {
        this(page, sections, false);
    }

    private Sectioned(Draw page, ColonyPanel.Page sections, boolean flowing) {
        this.page = page;
        this.sections = sections;
        this.editors = UiRegistrations.editors(sections);
        this.flowing = flowing;
    }

    // A page whose content takes its full height, scrolled with the editors and sections by one bar.
    static Sectioned flowing(Draw page, ColonyPanel.Page sections) {
        return new Sectioned(page, sections, true);
    }

    @Override
    public UIElement build(Player player) {
        UIElement own = page.build(player);
        UIElement shown = Rows.page();
        UIElement above = Rows.page();
        if (!editors.isEmpty()) {
            above.addChildren(launchers(player));
        }
        above.addChildren(new PanelSections(sections).build(player, shown));
        if (flowing) {
            // Each part keeps its full height, so the one scroll view around them all is what scrolls.
            for (UIElement part : List.of(above, own)) {
                part.layout(layout -> layout.flexGrow(0).flexShrink(0));
            }
            ScrollerView whole = Rows.fills(Rows.box());
            whole.addScrollViewChild(Rows.page().addChildren(above, own));
            shown.addChildren(whole);
            shown.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
            return shown;
        }
        // The editors and sections scroll in a band of their own, as tall as they are up to a share of the page, so
        // the page's own content keeps the rest however many sections have rows.
        ScrollerView band = Rows.box();
        band.layout(layout -> layout.widthPercent(100).height(0).flexShrink(0));
        band.addScrollViewChild(above);
        float[] laid = {0};
        shown.addEventListener(UIEvents.TICK, event -> {
            float height = Math.min(above.getSizeHeight(), shown.getSizeHeight() * BAND);
            if (Math.abs(height - laid[0]) > 0.5f) {
                laid[0] = height;
                band.layout(layout -> layout.height(height));
            }
        });
        shown.addChildren(band, own);
        shown.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return shown;
    }

    // One button per editor, wrapping onto further lines when the page is narrow.
    private UIElement launchers(Player player) {
        UIElement strip = Rows.strip();
        strip.layout(layout -> layout.flexWrap(FlexWrap.WRAP).gapAll(Rows.GAP).flexShrink(0));
        Tokens.name(strip, "folkways.book.editors." + sections.name().toLowerCase(Locale.ROOT));
        for (Editor editor : editors) {
            Button open = Rows.wearing();
            open.layout(layout -> layout.widthAuto().height(20).flexShrink(0).gapAll(Rows.GAP).paddingHorizontal(4));
            open.addChildren(Rows.icon(SettingsPage.iconOf(editor.icon())),
                Rows.worn(Component.translatable(editor.nameKey())));
            open.setOnClick(event -> editor(player, editor).open());
            // The same click installs the server's bindings in the same order as the client's.
            open.setOnServerClick(event -> editor(player, editor));
            Tokens.name(open, "folkways.book.open." + Tokens.of(editor.id()));
            strip.addChildren(open);
        }
        return strip;
    }

    @Override
    public List<UIElement> actions() {
        return page.actions();
    }

    @Override
    public boolean titled() {
        return page.titled();
    }

    @Override
    public void windows(Player player, Windows desk) {
        page.windows(player, desk);
        this.desk = desk;
    }

    private Pane editor(Player player, Editor editor) {
        // Keep the bindings for this book's lifetime. They deliver changes to the editor's data in place.
        return opened.computeIfAbsent(editor.id(), id -> {
            Draw drawn = editor.draw().get();
            UIElement content = drawn.build(player);
            List<UIElement> actions = drawn.actions();
            UIElement body = Rows.page();
            if (!actions.isEmpty()) {
                body.addChildren(Rows.actions(actions.toArray(UIElement[]::new)));
            }
            body.addChildren(content);
            body.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
            drawn.windows(player, desk);
            return desk.add(Tokens.of(id), Component.translatable(editor.nameKey()), body,
                editor.width(), editor.height());
        });
    }
}
