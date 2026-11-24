package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.card.client.CardView;
import io.github.izakyl.folkways.core.card.client.ClientCards;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.ColonyPanel;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.engine.colony.PanelFacilities;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * The modules' sections of one book page ({@link ColonyPanel}): each a card of one list under the module's name, shown
 * while it has rows. The server says which sections have rows now; the client watches those while the page is in
 * sight and draws each card as its status comes, not while the player types in it.
 */
final class PanelSections {
    private static final int PERIOD = 20;

    private final ColonyPanel.Page page;
    private final UIElement element = new UIElement()
        .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(5).flexShrink(0));
    private final Map<UUID, Section> sections = new LinkedHashMap<>();
    private UIElement host;
    private UUID colony;
    private long readAt = -PERIOD;
    private Tag read = new CompoundTag();

    private static final class Section {
        private final UIElement element;
        private final UIElement card = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100));
        private final UIElement heading;
        private Object drawn;
        private CardView view;

        Section(Component title) {
            heading = Rows.heading(title);
            element = new UIElement()
                .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).flexShrink(0))
                .addChildren(heading, card);
        }
    }

    PanelSections(ColonyPanel.Page page) {
        this.page = page;
    }

    /** Builds the sections' element, to be placed in {@code host}, an element shown whenever the page is. */
    UIElement build(Player player, UIElement host) {
        this.host = host;
        Tokens.name(element, "folkways.book.sections." + page.name().toLowerCase(java.util.Locale.ROOT));
        element.setDisplay(false);
        host.addSyncValue(DataBindingBuilder.tagS2C(() -> tag(player))
            .onSyncReceived(tag -> listed(player, tag))
            .build()
            .getSyncValue());
        host.addEventListener(UIEvents.TICK, event -> draw());
        return element;
    }

    /** Whether any section has rows to show now. */
    boolean any() {
        return sections.values().stream().anyMatch(section -> section.element.isDisplayed());
    }

    private Tag tag(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return new CompoundTag();
        }
        long now = level.get().getServer().getTickCount();
        if (now - readAt < PERIOD && now >= readAt) {
            return read;
        }
        readAt = now;
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", colony.get().id());
        ListTag list = new ListTag();
        for (PanelFacilities.Section section : PanelFacilities.of(colony.get(), page)) {
            boolean rows;
            try {
                rows = section.loaded(level.get().getServer())
                    .map(at -> !section.rows(at, section.source(), Map.of()).isEmpty()).orElse(false);
            } catch (RuntimeException unreadable) {
                rows = false;
            }
            if (rows) {
                CompoundTag entry = new CompoundTag();
                entry.putUUID("id", section.id());
                entry.put("title", Pages.text(player.registryAccess(), section.title()));
                list.add(entry);
            }
        }
        tag.put("sections", list);
        read = tag;
        return tag;
    }

    private void listed(Player player, Tag tag) {
        if (!(tag instanceof CompoundTag data) || !data.hasUUID("colony")) {
            sections.clear();
            element.clearAllChildren();
            element.setDisplay(false);
            return;
        }
        colony = data.getUUID("colony");
        Map<UUID, Component> listed = new LinkedHashMap<>();
        for (Tag each : data.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) each;
            listed.put(entry.getUUID("id"), Pages.readText(player.registryAccess(), entry.get("title")));
        }
        if (!List.copyOf(listed.keySet()).equals(List.copyOf(sections.keySet()))) {
            sections.clear();
            element.clearAllChildren();
            listed.forEach((id, title) -> {
                Section section = new Section(title);
                Tokens.name(section.element, "folkways.book.section." + id);
                sections.put(id, section);
                element.addChildren(section.element);
            });
        }
        BookCards.want(host, colony, new ArrayList<>(sections.keySet()));
        draw();
    }

    private void draw() {
        boolean any = false;
        for (var each : sections.entrySet()) {
            Section section = each.getValue();
            CardStatus status = ClientCards.status(each.getKey()).filter(told -> told.colony().equals(colony))
                .orElse(null);
            if (status != null && status != section.drawn) {
                section.drawn = status;
                if (section.view == null) {
                    section.view = CardView.of(status, BookCards.INLINE);
                    section.card.addChildren(section.view.card());
                } else {
                    section.view = section.view.refresh(status);
                }
            }
            boolean rows = status == null || status.rows().values().stream().anyMatch(list -> !list.isEmpty());
            if (section.element.isDisplayed() != rows) {
                section.element.setDisplay(rows);
            }
            any |= rows;
        }
        if (element.isDisplayed() != any) {
            element.setDisplay(any);
        }
    }
}
