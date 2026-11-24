package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Every facility the colony has, whoever holds it, as a tree that folds like the lessons: a group for each way the
 * colony comes to have one that is drawn or kept (zones, routes, what its services keep, what players defined), with a
 * folder for each kind with more than one inside it; blocks taken in stand at the top as folders of their own kind
 * (beds, storage) between the routes and what is kept, since a kind already says what a block is. Troubled ones come
 * first everywhere, and a filter keeps only those. A press on a row opens the facility's card beside the book, its
 * compass shows the place.
 */
final class FacilitiesPage implements Draw {

    private final BookFacilities.Reader reader = new BookFacilities.Reader();
    private BookFacilities.Listing listing = BookFacilities.Listing.NONE;
    private boolean troubleOnly;

    private UIElement table;
    private Label empty;
    private Label unmatched;
    private Label more;

    @Override
    public UIElement build(Player player) {
        Toggle trouble = ScreenControls.tick();
        trouble.setOnToggleChanged(on -> {
            troubleOnly = on;
            show();
        });
        Tokens.name(trouble, "folkways.book.facilities.problems");
        Label troubleName = Rows.value(Component.translatable("folkways.book.facilities.problems"));
        troubleName.layout(layout -> layout.flexShrink(0));
        UIElement filters = Rows.strip().addChildren(Rows.spacer(), troubleName, trouble);

        table = Rows.table();
        Tokens.name(table, "folkways.book.facilities.list");
        empty = Rows.note(Component.translatable("folkways.book.facilities.empty"));
        Tokens.name(empty, "folkways.book.facilities.empty");
        unmatched = Rows.note(Component.translatable("folkways.book.facilities.unmatched"));
        more = Rows.note(Component.empty());
        empty.setDisplay(false);
        unmatched.setDisplay(false);
        more.setDisplay(false);
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(empty, unmatched, table, more));
        UIElement page = Rows.page().addChildren(filters, scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reader.tag(player))
            .onSyncReceived(tag -> {
                listing = BookFacilities.parse(player.registryAccess(), tag);
                show();
            }).build().getSyncValue());
        return page;
    }

    @Override
    public boolean titled() {
        return false;
    }

    private void show() {
        // The listing comes troubled first, and each kind keeps that order.
        Map<BookFacilities.Group, Map<ResourceLocation, List<BookFacilities.Entry>>> grouped =
            new EnumMap<>(BookFacilities.Group.class);
        for (BookFacilities.Entry entry : listing.entries()) {
            if (!troubleOnly || entry.trouble()) {
                grouped.computeIfAbsent(entry.group(), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(entry.kind(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        table.clearAllChildren();
        grouped.forEach((group, kinds) -> {
            if (group == BookFacilities.Group.BLOCKS) {
                ordered(kinds).forEach(entries -> table.addChildren(kind(entries, 0)));
            } else {
                table.addChildren(group(group, kinds));
            }
        });
        empty.setDisplay(listing.entries().isEmpty());
        unmatched.setDisplay(!listing.entries().isEmpty() && grouped.isEmpty());
        more.setDisplay(listing.more() > 0);
        more.setText(Component.translatable("folkways.book.facilities.more", listing.more()));
    }

    // A group's head over its kinds, those in trouble first, then by name. A kind of one, or a group of a single
    // kind, lists its rows without a folder of their own.
    private UIElement group(BookFacilities.Group group, Map<ResourceLocation, List<BookFacilities.Entry>> kinds) {
        List<List<BookFacilities.Entry>> ordered = ordered(kinds);
        UIElement children = Rows.table();
        for (List<BookFacilities.Entry> entries : ordered) {
            if (entries.size() == 1 || ordered.size() == 1) {
                entries.forEach(entry -> children.addChildren(row(entry, 1)));
            } else {
                children.addChildren(kind(entries, 1));
            }
        }
        Button head = LessonsPage.branch("folkways.book.facilities.group." + group.key(), group.title(),
            Optional.of(group.icon()), 0, true, children);
        counted(head, kinds.values().stream().flatMap(List::stream).toList());
        return Rows.table().addChildren(head, children);
    }

    // A kind's folder at a depth, over its rows one deeper.
    private UIElement kind(List<BookFacilities.Entry> entries, int depth) {
        BookFacilities.Entry first = entries.getFirst();
        UIElement rows = Rows.table();
        entries.forEach(entry -> rows.addChildren(row(entry, depth + 1)));
        Button head = LessonsPage.branch("folkways.book.facilities.kind." + Tokens.of(first.kind()),
            first.kindName(), Optional.of(first.icon()), depth, true, rows);
        counted(head, entries);
        return Rows.table().addChildren(head, rows);
    }

    private static List<List<BookFacilities.Entry>> ordered(Map<ResourceLocation, List<BookFacilities.Entry>> kinds) {
        return kinds.values().stream()
            .sorted(Comparator.comparing((List<BookFacilities.Entry> entries) -> !entries.getFirst().trouble())
                .thenComparing(entries -> entries.getFirst().kindName().getString()))
            .toList();
    }

    // How many a head holds, at its end: the troubled ones in red before the whole count.
    private static void counted(Button head, List<BookFacilities.Entry> entries) {
        long troubled = entries.stream().filter(BookFacilities.Entry::trouble).count();
        Component count = troubled == 0 ? Component.literal(String.valueOf(entries.size()))
            : Component.empty().append(Component.literal(String.valueOf(troubled))
                .withColor(BookFacilities.TROUBLE & 0xFFFFFF)).append(" / " + entries.size());
        Label label = Rows.worn(count);
        label.layout(layout -> layout.flexShrink(0).marginRight(Rows.GAP));
        label.textStyle(style -> style.textColor(Rows.QUIET));
        head.addChildren(label);
        head.style(style -> style.tooltips(Component.translatable("folkways.book.facilities.count", entries.size())));
    }

    private UIElement row(BookFacilities.Entry entry, int depth) {
        UIElement row = BookFacilities.row(listing.colony(), entry);
        row.layout(layout -> layout.paddingLeft(depth * LessonsPage.INDENT));
        return row;
    }
}
