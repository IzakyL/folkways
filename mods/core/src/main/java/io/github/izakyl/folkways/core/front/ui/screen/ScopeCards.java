package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.card.client.CardView;
import io.github.izakyl.folkways.core.card.client.ClientCards;
import io.github.izakyl.folkways.core.card.net.CardStatus;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.CoreSettings;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyScopes;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * The colony's settings, scope by scope, each its card: the colony's own, then each module's, then each trade's
 * tools. A scope folds under its name; the colony's stands open at first, and whatever the player opens stays open
 * for the rest of the session. Only open scopes are watched.
 */
final class ScopeCards {
    enum Group { COLONY, MODULES, TRADES }

    // Folded open by the player this session; the colony's own scope is open until folded.
    private static final Set<UUID> OPEN = new HashSet<>(Set.of(ColonyScopes.id(CoreSettings.CORE)));
    private static final Set<UUID> FOLDED = new HashSet<>();

    private final Map<Group, UIElement> groups = new LinkedHashMap<>();
    private final Map<UUID, Scope> scopes = new LinkedHashMap<>();
    private UUID colony;

    private static final class Scope {
        private final UIElement element;
        private final Label chevron = Rows.value(Component.literal(">"));
        private final UIElement card = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).paddingLeft(Rows.GAP));
        private Object drawn;
        private CardView view;

        Scope(UUID id, Component title, Runnable toggle) {
            Button head = new Button().noText();
            head.layout(layout -> layout.flexDirection(FlexDirection.ROW).widthPercent(100).height(Rows.ROW_HEIGHT - 4)
                .flexShrink(0).alignItems(AlignItems.CENTER).gapAll(Rows.GAP).paddingHorizontal(Rows.GAP));
            head.addClass("folkways_nav");
            head.setOverflowVisible(false);
            chevron.layout(layout -> layout.width(8).flexShrink(0));
            head.addChildren(chevron, Rows.name(title));
            head.setOnClick(event -> toggle.run());
            Tokens.name(head, "folkways.book.scope." + id);
            Tokens.name(card, "folkways.book.scope.card." + id);
            element = new UIElement()
                .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).flexShrink(0))
                .addChildren(head, card);
        }

        void open(boolean open) {
            card.setDisplay(open);
            chevron.setText(Component.literal(open ? "v" : ">"));
        }
    }

    UIElement build(Player player) {
        UIElement element = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(2).flexShrink(0));
        for (Group group : Group.values()) {
            UIElement list = new UIElement()
                .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1)
                    .flexShrink(0));
            list.addChildren(Rows.heading(Component.translatable("folkways.book.settings."
                + group.name().toLowerCase(java.util.Locale.ROOT))));
            list.setDisplay(false);
            groups.put(group, list);
            element.addChildren(list);
        }
        Tokens.name(element, "folkways.book.scopes");
        element.addSyncValue(DataBindingBuilder.tagS2C(() -> tag(player))
            .onSyncReceived(tag -> listed(player, tag))
            .build()
            .getSyncValue());
        element.addEventListener(UIEvents.TICK, event -> draw());
        return element;
    }

    private static Tag tag(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        if (colony.isEmpty()) {
            return new CompoundTag();
        }
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", colony.get().id());
        ListTag list = new ListTag();
        for (ColonyScopes.Scope scope : ColonyScopes.all(colony.get())) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("id", scope.id());
            entry.put("title", Pages.text(player.registryAccess(), scope.title()));
            entry.putString("group", (scope.scope().equals(CoreSettings.CORE) ? Group.COLONY
                : Vocations.of(scope.scope()).isPresent() ? Group.TRADES : Group.MODULES).name());
            list.add(entry);
        }
        tag.put("scopes", list);
        return tag;
    }

    private void listed(Player player, Tag tag) {
        scopes.clear();
        groups.values().forEach(list -> {
            UIElement heading = list.getChildren().getFirst();
            list.clearAllChildren();
            list.addChildren(heading);
            list.setDisplay(false);
        });
        if (!(tag instanceof CompoundTag data) || !data.hasUUID("colony")) {
            return;
        }
        colony = data.getUUID("colony");
        for (Tag each : data.getList("scopes", Tag.TAG_COMPOUND)) {
            CompoundTag entry = (CompoundTag) each;
            UUID id = entry.getUUID("id");
            Group group;
            try {
                group = Group.valueOf(entry.getString("group"));
            } catch (IllegalArgumentException unknown) {
                group = Group.MODULES;
            }
            Scope scope = new Scope(id, Pages.readText(player.registryAccess(), entry.get("title")), () -> fold(id));
            scope.open(open(id));
            scopes.put(id, scope);
            UIElement list = groups.get(group);
            list.addChildren(scope.element);
            list.setDisplay(true);
            BookCards.want(scope.card, colony, List.of(id));
        }
        draw();
    }

    private static boolean open(UUID id) {
        return OPEN.contains(id) && !FOLDED.contains(id);
    }

    private void fold(UUID id) {
        boolean open = !open(id);
        if (open) {
            OPEN.add(id);
            FOLDED.remove(id);
        } else {
            FOLDED.add(id);
        }
        Scope scope = scopes.get(id);
        if (scope != null) {
            scope.open(open);
        }
    }

    private void draw() {
        List<UUID> shown = new ArrayList<>(scopes.keySet());
        for (UUID id : shown) {
            Scope scope = scopes.get(id);
            if (!scope.card.isDisplayed()) {
                continue;
            }
            CardStatus status = ClientCards.status(id).filter(told -> told.colony().equals(colony)).orElse(null);
            if (status == scope.drawn) {
                continue;
            }
            scope.drawn = status;
            if (status != null && scope.view != null) {
                scope.view = scope.view.refresh(status);
            } else {
                scope.view = status == null ? null : CardView.of(status, BookCards.INLINE);
                scope.card.clearAllChildren();
                scope.card.addChildren(scope.view == null
                    ? Rows.note(Component.translatable("folkways.rts.facility.waiting"))
                    : scope.view.card());
            }
        }
    }
}
