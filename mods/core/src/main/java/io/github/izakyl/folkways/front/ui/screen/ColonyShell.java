package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIContainerMenu;
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
import dev.vfyjxf.taffy.style.TaffyPosition;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.api.ui.Windows;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.charter.Charters;
import io.github.izakyl.folkways.front.engine.charter.CurrentColony;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.ColonySites;
import io.github.izakyl.folkways.front.engine.colony.SiteBoard;
import io.github.izakyl.folkways.front.engine.net.FolkwaysUiNetworkEvents;
import io.github.izakyl.folkways.front.ui.UiRegistrations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.appliedenergistics.yoga.YogaJustify;

public final class ColonyShell
    implements FolkwaysUI, PlayerUIMenuType.PlayerUIHolder, ColonyAuthority.Panel {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "colony");
    public static final ResourceLocation SITE_ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "site");

    // The block a book was used on, kept from the click until the window built for it takes it.
    private static final Map<Player, BlockPos> AIMED = new WeakHashMap<>();

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

    private static final int WINDOW_WIDTH = 300;
    private static final int WINDOW_HEIGHT = 230;
    // Compact site lists and grouped forms each get enough room; smaller screens keep scrolling.
    private static final int SITE_WIDTH = 400;
    private static final int SITE_HEIGHT = 320;
    private static final int FORM_WIDTH = 380;
    private static final int FORM_HEIGHT = 340;

    private final Optional<UUID> colonyId;
    private final Optional<BlockPos> site;
    private final boolean atSite;
    private Map<ResourceLocation, Pane> opened = Map.of();
    private final ZoneSettings selectionSettings = ZoneSettings.create();
    private boolean editingSelection;

    public static void editSelection(Player player, UUID id) {
        of(player).ifPresent(shell -> shell.selectionSettings.hold(id));
    }

    private ColonyShell(Player player) {
        this(player, Optional.empty(), false);
    }

    private ColonyShell(Player player, Optional<BlockPos> at, boolean atSite) {
        site = at;
        this.atSite = atSite;
        colonyId = CurrentColony.id(player).or(() -> siteColonyId(player, at));
    }

    private static Optional<SiteBoard> answeringSite(Player player, Optional<BlockPos> at) {
        if (!(player instanceof ServerPlayer serverPlayer) || at.isEmpty()) {
            return Optional.empty();
        }
        BlockPos pos = at.get();
        ServerLevel level = serverPlayer.serverLevel();
        return ColonyAuthority.of(serverPlayer, Optional.of(pos))
            .flatMap(authority -> ColonyBoards.firstAt(level, authority.colony(), pos));
    }

    private static Optional<ResourceLocation> answeringPage(Player player, Optional<BlockPos> at) {
        return answeringSite(player, at).map(SiteBoard::page);
    }

    private static Optional<UUID> siteColonyId(Player player, Optional<BlockPos> at) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }
        return at.flatMap(pos -> ColonyGround.ofBlock(serverPlayer.serverLevel(), pos))
            .filter(colony -> Charters.member(serverPlayer.getServer(), colony, serverPlayer.getUUID()))
            .map(Colony::id);
    }

    private ModularUI createSiteUI(Player player) {
        Desk desk = new Desk();
        Window window = desk.panel(Window.of("site",
            Component.translatable("folkways.panel.title"),
            0f, 0f, SITE_WIDTH, SITE_HEIGHT, 220, 140));
        window.open();
        window.onDismiss(() -> {
            if (player instanceof ServerPlayer serverPlayer) {
                serverPlayer.closeContainer();
            }
        });
        UIElement panel = Rows.page();
        panel.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        Tokens.name(panel, "folkways.site.panel");
        panel.setDisplay(false);
        panel.setOverflowVisible(false);
        Button toggle = new Button()
            .setText(Component.translatable("folkways.member.toggle"))
            .setOnServerClick(event -> toggleMember(player));
        Tokens.name(toggle, "folkways.member.toggle");
        window.body().addChildren(panel, Rows.actions(toggle));
        Windows windows = (id, title, body) -> {
            Window opened = desk.add(Window.of(id, title,
                0, 0, WINDOW_WIDTH, WINDOW_HEIGHT, 220, 140));
            opened.body().addChildren(body);
            return opened;
        };
        Map<ResourceLocation, UIElement> pages = new LinkedHashMap<>();
        Map<ResourceLocation, Pane> panes = new LinkedHashMap<>();
        List<PageKind> listed = PageKinds.all();
        for (PageKind kind : PageKinds.every()) {
            if (PageKinds.CORE.contains(kind)) {
                continue;
            }
            if (listed.contains(kind)) {
                UIElement content = titled(kind, player, windows);
                content.setDisplay(false);
                pages.put(kind.id(), content);
                panel.addChildren(content);
            } else {
                panes.put(kind.id(), windows.add(Tokens.of(kind.id()),
                    Component.translatable(kind.nameKey()), windowed(kind, player, windows)));
            }
        }
        opened = Map.copyOf(panes);
        desk.root().addSyncValue(DataBindingBuilder.stringS2C(() -> answeringPage(player, site)
            .map(ResourceLocation::toString).orElse(""))
            .onSyncReceived(id -> {
                ResourceLocation target = ResourceLocation.tryParse(id);
                panel.setDisplay(target != null && pages.containsKey(target));
                pages.forEach((key, value) -> value.setDisplay(key.equals(target)));
            }).build().getSyncValue());
        desk.root().addSyncValue(DataBindingBuilder.boolS2C(() -> answeringSite(player, site)
            .map(answer -> answer.board().layout() == Board.Layout.FORM).orElse(false))
            .onSyncReceived(form -> window.preferSize(form ? FORM_WIDTH : SITE_WIDTH,
                form ? FORM_HEIGHT : SITE_HEIGHT)).build().getSyncValue());
        desk.root().addSyncValue(DataBindingBuilder.boolS2C(() -> member(player, site))
            .onSyncReceived(held -> toggle.setText(Component.translatable(
                held ? "folkways.member.leave" : "folkways.member.join")))
            .build().getSyncValue());
        return new ModularUI(UI.of(desk.root(),
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    ResourceLocation.fromNamespaceAndPath("folkways", "lss/desk.lss"))),
            screen -> screen), player);
    }

    private static boolean member(Player player, Optional<BlockPos> at) {
        if (!(player instanceof ServerPlayer serverPlayer) || at.isEmpty()) {
            return false;
        }
        ServerLevel level = serverPlayer.serverLevel();
        return ColonyAuthority.of(serverPlayer, at)
            .filter(authority -> ColonyGround.holds(level, authority.colony(), ColonySites.anchor(level, at.get())))
            .isPresent();
    }

    private void toggleMember(Player player) {
        if (player instanceof ServerPlayer serverPlayer) {
            ColonyAuthority.of(serverPlayer, site)
                .filter(authority -> authority.at().isPresent())
                .ifPresent(authority -> FolkwaysUiNetworkEvents.toggleColonyMember(serverPlayer, authority));
        }
    }

    public static void register() {
        PlayerUIMenuType.register(ID, ColonyShell::new);
        PlayerUIMenuType.register(SITE_ID,
            player -> new ColonyShell(player, Optional.ofNullable(AIMED.remove(player)), true));
    }

    @Override
    public Optional<UUID> colonyId() {
        return colonyId;
    }

    static Optional<ColonyShell> of(Player player) {
        return player.containerMenu instanceof ModularUIContainerMenu menu
            && menu.uiHolder instanceof ColonyShell shell
            ? Optional.of(shell) : Optional.empty();
    }

    public static void open(ServerPlayer player) {
        PlayerUIMenuType.openUI(player, ID);
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

    /** The window of one block the colony holds: its board alone, without the colony's other pages. */
    public static void openAt(ServerPlayer player, BlockPos at) {
        AIMED.put(player, at.immutable());
        PlayerUIMenuType.openUI(player, SITE_ID);
        AIMED.remove(player);
    }

    public static Optional<BlockPos> siteOf(Player player) {
        return of(player).flatMap(shell -> shell.site);
    }

    static void openPage(Player player, ResourceLocation page) {
        of(player).map(shell -> shell.opened.get(page)).ifPresent(Pane::open);
    }

    @Override
    public ModularUI createUI(Player player) {
        PageKinds.verify();
        if (atSite) {
            return createSiteUI(player);
        }
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
        UIElement navList = navGroup("folkways.ui.nav.production");
        UIElement preferences = navGroup("folkways.ui.nav.preferences");
        UIElement groups = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(6))
            .addChildren(coreNav, navList, preferences);
        ScrollerView nav = Rows.box();
        nav.layout(layout -> layout.width(NAV_WIDTH).flexShrink(0).minHeight(0));
        nav.addScrollViewChild(groups);
        Tokens.name(nav, "folkways.navigation");
        NavFold fold = new NavFold(nav);
        menu.setOnClick(event -> fold.toggle());

        UIElement pane = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
        pane.setOverflowVisible(false);

        Windows windows = (id, title, body) -> {
            Window window = desk.add(Window.of(id, title,
                0f, 0f, WINDOW_WIDTH, WINDOW_HEIGHT, 220, 140));
            window.body().addChildren(body);
            return window;
        };

        Map<ResourceLocation, UIElement> entriesByPage = new LinkedHashMap<>();
        List<PageKind> listed = new java.util.ArrayList<>(PageKinds.all());
        listed.removeAll(PageKinds.TRAILING);
        listed.addAll(PageKinds.TRAILING);
        for (PageKind kind : listed) {
            UIElement page = titled(kind, player, windows);
            page.setDisplay(kind.equals(listed.get(0)));
            pages.put(kind.id(), page);
            pane.addChildren(page);
            UIElement entries = PageKinds.TRAILING.contains(kind) ? preferences
                : PageKinds.CORE.contains(kind) ? coreNav : navList;
            UIElement navigation = entry(kind, marks, () -> {
                show(pages, marks, kind.id());
                fold.close();
            });
            navigation.setDisplay(PageKinds.CORE.contains(kind));
            entriesByPage.put(kind.id(), navigation);
            entries.addChildren(navigation);
        }
        mark(marks, listed.get(0).id());
        navList.setDisplay(navList.getChildren().size() > 1);

        UIElement workspace = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(nav, pane);
        workspace.addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.LAYOUT_CHANGED,
            event -> fold.lay(workspace.getSizeWidth()));

        UIElement chooser = new Chooser().build(player, () -> choosing[0] = false);
        chooser.setDisplay(false);
        UIElement configuration = Rows.page();
        configuration.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        Tokens.name(configuration, "folkways.selection.page");
        UIElement draft = Drawing.create().build();
        draft.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        UIElement settings = selectionSettings.build(player);
        settings.setDisplay(false);
        configuration.addChildren(draft, settings);
        configuration.setDisplay(false);
        panel.body().addChildren(chooser, workspace, configuration);
        desk.root().addSyncValue(DataBindingBuilder.tagS2C(() -> selectionSettings.reading(player))
            .onSyncReceived(tag -> {
                editingSelection = selectionSettings.show(player, tag);
                settings.setDisplay(editingSelection);
            }).build().getSyncValue());
        String[] requestedSelection = {""};
        boolean[] selectionRequested = {false};
        var selectedBinding = DataBindingBuilder.stringC2S(id -> {
            if (!id.isEmpty()) {
                try { selectionSettings.hold(UUID.fromString(id)); }
                catch (IllegalArgumentException ignored) { }
            }
        }).build();
        selectedBinding.setRemoteDataSource(com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource.of(
            ignored -> { }, () -> requestedSelection[0]));
        desk.root().addSyncValue(selectedBinding.getSyncValue());
        desk.root().addEventListener(com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents.TICK, event -> {
            if (!com.lowdragmc.lowdraglib2.LDLib2.isRemote()) return;
            if (!selectionRequested[0]) {
                requestedSelection[0] = Drawing.selected();
                selectionRequested[0] = true;
            }
            boolean drawing = Drawing.active();
            boolean configuring = drawing || editingSelection;
            configuration.setDisplay(configuring);
            draft.setDisplay(drawing && !editingSelection);
            chooser.setDisplay(!configuring && (!settled[0] || choosing[0]));
            workspace.setDisplay(!configuring && !chooser.isDisplayed());
            menu.setDisplay(fold.folded && workspace.isDisplayed());
        });

        Map<ResourceLocation, Pane> panes = new LinkedHashMap<>();
        for (PageKind kind : PageKinds.every()) {
            if (listed.contains(kind)) {
                continue;
            }
            panes.put(kind.id(), windows.add(Tokens.of(kind.id()),
                Component.translatable(kind.nameKey()), windowed(kind, player, windows)));
        }
        opened = Map.copyOf(panes);

        UIElement root = desk.root();
        root.addSyncValue(DataBindingBuilder.stringS2C(() -> Pages.colonyOf(player)
            .map(colony -> listed.stream().filter(kind -> PageKinds.shows(colony, kind.id()))
                .map(kind -> kind.id().toString()).collect(java.util.stream.Collectors.joining("|")))
            .orElse(""))
            .onSyncReceived(value -> {
                var visible = java.util.Arrays.asList(value.split("\\|"));
                entriesByPage.forEach((id, navigation) -> navigation.setDisplay(visible.contains(id.toString())));
                boolean hidden = pages.entrySet().stream()
                    .anyMatch(entry -> entry.getValue().isDisplayed() && !visible.contains(entry.getKey().toString()));
                if (hidden) show(pages, marks, PageKinds.CITIZENS.id());
                navList.setDisplay(listed.stream().filter(kind -> !PageKinds.CORE.contains(kind)
                    && !PageKinds.TRAILING.contains(kind) && visible.contains(kind.id().toString())).count() > 0);
            }).build().getSyncValue());
        root.addSyncValue(DataBindingBuilder.boolS2C(() -> Pages.colonyOf(player).isPresent())
            .onSyncReceived(kept -> {
                settled[0] = kept;
                workspace.setDisplay(kept && !choosing[0]);
                chooser.setDisplay(!kept || choosing[0]);
            })
            .build()
            .getSyncValue());
        root.addSyncValue(DataBindingBuilder.componentS2C(() -> heading(player))
            .onSyncReceived(title -> heading.setText(title))
            .build()
            .getSyncValue());
        return new ModularUI(UI.of(root,
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "lss/desk.lss"))),
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

    // With less room than a page needs beside it, the list of pages folds away behind the menu button and opens over
    // the page when it is pressed; choosing a page folds it again. The room is counted in the interface's own units,
    // so a larger interface folds it on a wider screen.
    private static final class NavFold {

        private final UIElement nav;
        private boolean folded;

        private NavFold(UIElement nav) {
            this.nav = nav;
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
            nav.layout(layout -> layout.positionType(folded ? TaffyPosition.ABSOLUTE : TaffyPosition.RELATIVE)
                .left(0).top(0).bottom(0));
            nav.style(style -> style.zIndex(folded ? 1 : 0));
            nav.setDisplay(!folded);
        }

        private void toggle() {
            if (folded) {
                nav.setDisplay(!nav.isDisplayed());
            }
        }

        private void close() {
            if (folded) {
                nav.setDisplay(false);
            }
        }
    }

    private static UIElement navGroup(String nameKey) {
        UIElement group = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        return group.addChildren(Rows.group(Component.translatable(nameKey)));
    }

    private static Button entry(PageKind kind, Map<ResourceLocation, UIElement> marks,
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

    private static Draw drawOf(PageKind kind) {
        return UiRegistrations.draw(kind.id()).orElseGet(() -> DomainPage.of(kind));
    }

    // A page in the panel: its name on the left of the title line, its figures and buttons on the right.
    private static UIElement titled(PageKind kind, Player player, Windows windows) {
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

    // A page in its own window, whose title bar already names it.
    private static UIElement windowed(PageKind kind, Player player, Windows windows) {
        Draw drawn = drawOf(kind);
        UIElement body = drawn.build(player);
        drawn.windows(player, windows);
        List<UIElement> actions = drawn.actions();
        if (actions.isEmpty()) {
            return body;
        }
        UIElement page = Rows.page().addChildren(Rows.actions(actions.toArray(UIElement[]::new)), body);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }
}
