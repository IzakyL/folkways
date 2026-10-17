package io.github.izakyl.folkways.plugins.build;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

// The blueprints there are to set down: the server's shelf and the player's own folder. Which there are and how one
// is held are the client's to know, so on the server this page is an empty shelf, with no synced value to disagree on.
public final class BlueprintsPage implements Draw {

    static final String TOKEN = "folkways.blueprints";

    public record Entry(ResourceLocation id, Component name, boolean own) {
    }

    private static Supplier<List<Entry>> shelf = List::of;
    private static Consumer<Entry> taking = entry -> { };

    public static void install(Supplier<List<Entry>> shelved, Consumer<Entry> takes) {
        shelf = shelved;
        taking = takes;
    }

    @Override
    public UIElement build(Player viewer) {
        return viewer.level().isClientSide() ? shelfOf(shelf.get(), taking) : shelfOf(List.of(), entry -> { });
    }

    // The list itself, for this page and for the picker the view from above opens on its own.
    static UIElement shelfOf(List<Entry> entries, Consumer<Entry> takes) {
        UIElement table = Rows.table();
        UIElement body = Rows.page().addChildren(Rows.note(Component.translatable(TOKEN + ".help")), table);
        if (entries.isEmpty()) {
            body.addChildren(Rows.text(Component.translatable(TOKEN + ".empty")));
        }
        for (Entry entry : entries) {
            table.addChildren(row(entry, takes));
        }
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(body);
        return scroller;
    }

    private static Button row(Entry entry, Consumer<Entry> takes) {
        Component name = entry.own()
            ? Component.translatable(TOKEN + ".own", entry.name())
            : entry.name();
        Button row = new Button().setText(name);
        row.layout(layout -> layout.widthPercent(100).height(Rows.ROW_HEIGHT - 2));
        row.setOnClick(event -> takes.accept(entry));
        Tokens.name(row, TOKEN + ".entry." + Tokens.of(entry.id()));
        return row;
    }
}
