package io.github.izakyl.folkways.core.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.card.client.CardView;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import io.github.izakyl.folkways.core.front.api.ui.Windows;
import io.github.izakyl.folkways.core.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.core.colony.charter.Charters;
import io.github.izakyl.folkways.core.colony.charter.CurrentColony;
import io.github.izakyl.folkways.core.front.ui.UiRegistrations;
import io.github.izakyl.folkways.core.front.ui.screen.PageKinds.BookPage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.appliedenergistics.yoga.YogaJustify;

public final class ColonyShell
    implements FolkwaysUI, PlayerUIMenuType.PlayerUIHolder, ColonyAuthority.Panel {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysCoreMod.MOD_ID, "colony");
    private static final Map<Player, ResourceLocation> REQUESTED_PAGE = new WeakHashMap<>();

    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 310;

    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 200;

    private static final int NAV_WIDTH = 104;
    // The narrowest a page is given beside the list of pages; with less room the list folds away (see NavFold).
    private static final int PAGE_MIN = 360;

    private static final int MENU_SIZE = (int) Window.TITLE_HEIGHT - 4;
    private static final int MENU_BAR = 0xFF3B2A1A;

    private static final int MARK_WIDTH = 2;
    private static final int MARK_ON = 0xFF9A3324;

    private static final int CARD_WIDTH = 340;
    private static final int CARD_HEIGHT = 320;

    private final Optional<UUID> colonyId;
    private final Optional<ResourceLocation> initialPage;
    private ColonyShell(Player player) {
        colonyId = CurrentColony.id(player);
        initialPage = Optional.ofNullable(REQUESTED_PAGE.remove(player));
    }

    public static void register() {
        PlayerUIMenuType.register(ID, ColonyShell::new);
    }

    @Override
    public Optional<UUID> colonyId() {
        return colonyId;
    }

    public static void open(ServerPlayer player) {
        PlayerUIMenuType.openUI(player, ID);
    }

    /** A requested main page still has to be declared, visible, and owned by the book's current holder. */
    public static void open(ServerPlayer player, ResourceLocation page) {
        if (CurrentColony.of(player).isEmpty()
            || PageKinds.all().stream().noneMatch(kind -> kind.id().equals(page))) {
            return;
        }
        REQUESTED_PAGE.put(player, page);
        try {
            open(player);
        } finally {
            REQUESTED_PAGE.remove(player);
        }
    }

    // The panel again, for another colony: on the next tick, once the press that asked for it is done with this one.
    static void reopen(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (!player.hasDisconnected()) {
                open(player);
            }
        }));
    }

    @Override
    public ModularUI createUI(Player player) {
        Desk desk = new Desk();
        Window panel = desk.panel(Window.of("colony",
            Component.translatable("folkways.panel.title"),
            0f, 0f, PANEL_WIDTH, PANEL_HEIGHT, MIN_WIDTH, MIN_HEIGHT));
        // The title names the colony, and pressed, lists the others the player keeps in place of the pages.
        boolean[] choosing = {false};
        boolean[] settled = {false};
        Button heading = new Button().setText(Component.translatable("folkways.panel.title"));
        heading.layout(layout -> layout.widthAuto().flexShrink(1).minWidth(0).height(Window.TITLE_HEIGHT - 4));
        heading.setOnClick(event -> choosing[0] = !choosing[0]);
        Tokens.name(heading, "folkways.charter.heading");
        Button menu = menu();
        panel.lead(new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER)
                .gapAll(Rows.GAP).flexShrink(1).minWidth(0))
            .addChildren(menu, heading));
        panel.open();
        panel.onDismiss(() -> {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
        });

        Map<ResourceLocation, UIElement> pages = new LinkedHashMap<>();
        Map<ResourceLocation, UIElement> marks = new LinkedHashMap<>();
        // Every group scrolls in one list, so every entry is as wide as the next and none is cut short mid-panel.
        UIElement coreNav = navGroup("folkways.ui.nav.colony");
        UIElement preferences = navGroup("folkways.ui.nav.preferences");
        UIElement groups = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(6))
            .addChildren(coreNav, preferences);
        ScrollerView nav = Rows.box();
        nav.layout(layout -> layout.width(NAV_WIDTH).flexShrink(0).minHeight(0));
        nav.style(style -> style.background(DeskTextures.paper(true)));
        nav.addScrollViewChild(groups);
        Tokens.name(nav, "folkways.navigation");
        UIElement pane = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
        pane.setOverflowVisible(false);
        Tokens.name(pane, "folkways.page.content");
        NavFold fold = new NavFold(nav, pane);
        menu.setOnClick(event -> fold.toggle());

        Windows windows = (id, title, body, width, height) -> {
            Window window = desk.add(Window.of(id, title,
                0f, 0f, width, height, Math.min(220, width), Math.min(140, height)));
            window.body().addChildren(body);
            return window;
        };

        List<BookPage> listed = PageKinds.all();
        for (BookPage kind : listed) {
            UIElement page = titled(kind, player, windows);
            page.setDisplay(kind.equals(listed.get(0)));
            pages.put(kind.id(), page);
            pane.addChildren(page);
            UIElement entries = PageKinds.TRAILING.contains(kind) ? preferences : coreNav;
            entries.addChildren(entry(kind, marks, () -> {
                show(pages, marks, kind.id());
                fold.close();
            }));
        }
        mark(marks, listed.get(0).id());

        UIElement workspace = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(nav, pane);
        Tokens.name(workspace, "folkways.workspace");
        workspace.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,
            event -> fold.lay(workspace.getSizeWidth()));

        UIElement chooser = new Chooser().build(player, () -> choosing[0] = false);
        chooser.setDisplay(false);
        panel.body().addChildren(chooser, workspace);
        desk.root().addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK, event -> {
            if (!com.lowdragmc.lowdraglib2.LDLib2.isRemote()) {
                return;
            }
            chooser.setDisplay(!settled[0] || choosing[0]);
            workspace.setDisplay(!chooser.isDisplayed());
            menu.setDisplay(fold.folded && workspace.isDisplayed());
        });

        // A facility's card, pressed on any page, opens beside the panel; it follows the facility pressed last.
        Window card = desk.add(Window.of("book_card", Component.translatable(CardView.ID + "window"),
            0f, 0f, CARD_WIDTH, CARD_HEIGHT, 180f, 100f));
        if (com.lowdragmc.lowdraglib2.LDLib2.isRemote()) {
            BookCards.attach(desk.root(), card);
        }

        UIElement root = desk.root();
        root.addSyncValue(DataBindingBuilder.boolS2C(() -> Pages.colonyOf(player).isPresent())
            .onSyncReceived(kept -> {
                settled[0] = kept;
                workspace.setDisplay(kept && !choosing[0]);
                chooser.setDisplay(!kept || choosing[0]);
            })
            .build()
            .getSyncValue());
        root.addSyncValue(DataBindingBuilder.stringS2C(() -> initialPage
            .filter(page -> Pages.colonyOf(player).isPresent())
            .map(ResourceLocation::toString).orElse(""))
            .onSyncReceived(value -> {
                ResourceLocation target = ResourceLocation.tryParse(value);
                if (target != null && pages.containsKey(target)) {
                    show(pages, marks, target);
                }
            }).build().getSyncValue());
        root.addSyncValue(DataBindingBuilder.componentS2C(() -> heading(player))
            .onSyncReceived(title -> heading.setText(title))
            .build()
            .getSyncValue());
        desk.launchers();
        return new ModularUI(UI.of(root,
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways_core", "lss/desk.lss"))),
            screen -> screen), player);
    }

    // Three bars at the head of the title bar, shown while the list of pages is folded away.
    private static Button menu() {
        Button menu = new Button().noText();
        menu.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).justifyContent(YogaJustify.CENTER)
            .alignItems(AlignItems.CENTER).gapAll(2).width(MENU_SIZE).height(MENU_SIZE).flexShrink(0));
        for (int bar = 0; bar < 3; bar++) {
            menu.addChildren(new UIElement()
                .layout(layout -> layout.width(MENU_SIZE - 8).height(2).flexShrink(0))
                .style(style -> style.background(new ColorRectTexture(MENU_BAR))));
        }
        menu.setDisplay(false);
        Tokens.name(menu, "folkways.navigation.menu");
        return menu;
    }

    // With less room than a page needs beside it, navigation and the selected page take turns using the workspace.
    // Hiding the page also removes its controls from input while the navigation is open. Choosing a page brings it
    // back; widening the workspace restores both columns. Room is counted in the interface's own units.
    private static final class NavFold {

        private final UIElement nav;
        private final UIElement pane;
        private boolean folded;
        private boolean open;

        private NavFold(UIElement nav, UIElement pane) {
            this.nav = nav;
            this.pane = pane;
        }

        private void lay(float width) {
            // A workspace put away for the colony chooser has no width to go by.
            if (width <= 0f) {
                return;
            }
            boolean narrow = width < NAV_WIDTH + Rows.GAP + PAGE_MIN;
            if (narrow == folded) {
                return;
            }
            folded = narrow;
            open = false;
            nav.layout(layout -> {
                if (folded) {
                    layout.widthPercent(100);
                } else {
                    layout.width(NAV_WIDTH);
                }
            });
            display();
        }

        private void toggle() {
            if (folded) {
                open = !open;
                display();
            }
        }

        private void close() {
            if (folded) {
                open = false;
                display();
            }
        }

        private void display() {
            nav.setDisplay(!folded || open);
            pane.setDisplay(!folded || !open);
        }
    }

    private static UIElement navGroup(String nameKey) {
        UIElement group = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        return group.addChildren(Rows.group(Component.translatable(nameKey)));
    }

    private static Button entry(BookPage kind, Map<ResourceLocation, UIElement> marks,
            Runnable chosen) {
        UIElement mark = new UIElement()
            .layout(layout -> layout.width(MARK_WIDTH).heightPercent(100).flexShrink(0))
            .style(style -> style.background(new ColorRectTexture(MARK_ON)));
        mark.setDisplay(false);
        marks.put(kind.id(), mark);

        Button entry = new Button().noText();
        entry.layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(Rows.ROW_HEIGHT).flexShrink(0)
            .alignItems(AlignItems.CENTER).gapAll(Rows.GAP - 1).paddingAll(1));
        entry.addClass("folkways_nav");
        entry.setOverflowVisible(false);
        entry.addChildren(mark, Rows.icon(SettingsPage.iconOf(kind.icon())),
            Rows.name(Component.translatable(kind.nameKey())));
        entry.setOnClick(event -> chosen.run());
        Tokens.name(entry, kind.nameKey());
        return entry;
    }

    private static void show(Map<ResourceLocation, UIElement> pages,
            Map<ResourceLocation, UIElement> marks, ResourceLocation target) {
        pages.forEach((id, page) -> page.setDisplay(id.equals(target)));
        mark(marks, target);
    }

    private static void mark(Map<ResourceLocation, UIElement> marks, ResourceLocation target) {
        marks.forEach((id, bar) -> {
            boolean selected = id.equals(target);
            bar.setDisplay(selected);
            if (bar.getParent() instanceof Button button) {
                button.buttonStyle(style -> style.baseTexture(
                    new ColorRectTexture(selected ? 0xFFD6C497 : 0x00000000)));
            }
            if (selected) {
                bar.getParent().addClass("folkways_selected");
            } else {
                bar.getParent().removeClass("folkways_selected");
            }
        });
    }

    private static Component heading(Player player) {
        Component title = player instanceof ServerPlayer serverPlayer
            ? Pages.colonyOf(player)
                .map(colony -> Charters.title(serverPlayer.getServer(), Charters.of(serverPlayer.getServer(), colony)))
                .orElseGet(() -> Component.translatable("folkways.panel.title"))
            : Component.translatable("folkways.panel.title");
        return Component.translatable("folkways.charter.heading", title);
    }

    private static Draw drawOf(BookPage kind) {
        return UiRegistrations.draw(kind.id()).orElseThrow();
    }

    // A page in the panel: its name on the left of the title line, its figures and buttons on the right.
    private static UIElement titled(BookPage kind, Player player, Windows windows) {
        Draw drawn = drawOf(kind);
        UIElement body = drawn.build(player);
        drawn.windows(player, windows);
        List<UIElement> actions = drawn.actions();
        UIElement page = Rows.page();
        if (drawn.titled()) {
            UIElement title = Rows.title(Component.translatable(kind.nameKey()));
            actions.forEach(title::addChildren);
            page.addChildren(title);
        } else if (!actions.isEmpty()) {
            page.addChildren(Rows.actions(actions.toArray(UIElement[]::new)));
        }
        page.addChildren(body);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }
}
