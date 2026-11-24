package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.engine.colony.Residents;
import io.github.izakyl.folkways.core.inventory.api.InventorySnapshot;
import io.github.izakyl.folkways.core.resident.api.Body;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * The book's first page: a few figures for the colony as a whole, then what needs the player's attention, the
 * modules' problems and the facilities something stands in the way of, each pressed to open its card.
 */
final class OverviewPage implements Draw {
    private static final int PERIOD = 20;
    private static final int FIGURE_HEIGHT = 26;

    private final BookFacilities.Reader facilities = new BookFacilities.Reader();
    private long readAt = -PERIOD;
    private CompoundTag figures = new CompoundTag();

    @Override
    public UIElement build(Player player) {
        Figure residents = new Figure("residents", "player_head");
        Figure idle = new Figure("idle", "clock");
        Figure stores = new Figure("stores", "chest");
        Figure listed = new Figure("facilities", "lectern");
        Figure problems = new Figure("problems", "barrier");
        UIElement strip = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP).widthPercent(100)
                .gapAll(Rows.GAP).flexShrink(0))
            .addChildren(residents.element, idle.element, stores.element, listed.element, problems.element);
        Tokens.name(strip, "folkways.book.figures");

        PanelSections sections = new PanelSections(ColonyPanel.Page.OVERVIEW);
        UIElement troubled = Rows.table();
        Tokens.name(troubled, "folkways.book.attention.facilities");
        Label calm = Rows.note(Component.translatable("folkways.book.attention.none"));
        Tokens.name(calm, "folkways.book.attention.none");
        UIElement attention = Rows.heading(Component.translatable("folkways.book.attention"));
        UIElement content = Rows.page();
        content.addChildren(strip, attention, sections.build(player, content), troubled, calm);
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(content);
        UIElement page = Rows.page().addChildren(scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> figures(player))
            .onSyncReceived(tag -> {
                if (tag instanceof CompoundTag data) {
                    residents.set(Component.literal(Integer.toString(data.getInt("residents"))));
                    idle.set(Component.literal(Integer.toString(data.getInt("idle"))));
                    stores.set(data.getInt("stores") == 0 ? Component.literal("-")
                        : Component.literal(data.getInt("observed") + "/" + data.getInt("stores")));
                    idle.warn(data.getInt("idle") > 0 && data.getInt("idle") == data.getInt("residents"));
                }
            }).build().getSyncValue());
        int[] trouble = {0};
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> facilities.tag(player))
            .onSyncReceived(tag -> {
                BookFacilities.Listing listing = BookFacilities.parse(player.registryAccess(), tag);
                troubled.clearAllChildren();
                List<BookFacilities.Entry> shown = listing.entries().stream()
                    .filter(BookFacilities.Entry::trouble).toList();
                shown.forEach(entry -> troubled.addChildren(BookFacilities.row(listing.colony(), entry)));
                trouble[0] = shown.size();
                listed.set(Component.literal(Integer.toString(listing.entries().size() + listing.more())));
                problems.set(Component.literal(Integer.toString(shown.size())));
                problems.warn(!shown.isEmpty());
            }).build().getSyncValue());
        // The page ticks it: the line hides itself while anything needs attention, and a hidden element does not tick.
        content.addEventListener(UIEvents.TICK, event -> {
            boolean quiet = trouble[0] == 0 && !sections.any();
            if (calm.isDisplayed() != quiet) {
                calm.setDisplay(quiet);
            }
        });
        return page;
    }

    @Override
    public boolean titled() {
        return false;
    }

    private Tag figures(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return new CompoundTag();
        }
        MinecraftServer server = level.get().getServer();
        long now = server.getTickCount();
        if (now - readAt < PERIOD && now >= readAt) {
            return figures;
        }
        readAt = now;
        List<Body> bodies = Residents.of(colony.get(), server);
        InventorySnapshot.Coverage coverage = colony.get().inventory().snapshot().coverage();
        CompoundTag tag = new CompoundTag();
        tag.putInt("residents", bodies.size());
        tag.putInt("idle", (int) bodies.stream().filter(body -> body.doing().isEmpty()).count());
        tag.putInt("stores", coverage.stores().size());
        tag.putInt("observed", (int) coverage.stores().values().stream()
            .filter(state -> state == InventorySnapshot.State.OBSERVED).count());
        figures = tag;
        return tag;
    }

    /** One figure: an icon, the number large enough to find at a glance, and what it counts. */
    private static final class Figure {
        private final UIElement element;
        private final Label value = Rows.value(Component.literal("-"));
        private boolean warned;

        Figure(String key, String icon) {
            value.setText(Component.literal("-").withStyle(ChatFormatting.BOLD));
            Label label = Rows.value(Component.translatable("folkways.book.figure." + key));
            label.textStyle(style -> style.textColor(Rows.QUIET));
            UIElement words = new UIElement()
                .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).flexShrink(1).minWidth(0))
                .addChildren(value, label);
            element = new UIElement()
                .layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
                    .height(FIGURE_HEIGHT).paddingHorizontal(Rows.GAP).gapAll(Rows.GAP).flexGrow(1).flexShrink(0)
                    .minWidth(62))
                .addChildren(Rows.icon(SettingsPage.iconOf(ResourceLocation.withDefaultNamespace(icon))), words);
            element.addClass("folkways_card");
            element.style(style -> style.tooltips(Component.translatable("folkways.book.figure." + key + ".tip")));
            Tokens.name(element, "folkways.book.figure." + key);
            Tokens.name(value, "folkways.book.figure." + key + ".value");
        }

        void set(Component shown) {
            value.setText(shown.copy().withStyle(ChatFormatting.BOLD));
        }

        void warn(boolean warn) {
            if (warned != warn) {
                warned = warn;
                value.textStyle(style -> style.textColor(warn ? BookFacilities.TROUBLE : Rows.INK));
            }
        }
    }
}
