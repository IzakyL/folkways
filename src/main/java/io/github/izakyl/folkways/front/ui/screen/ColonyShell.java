package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.factory.PlayerUIMenuType;
import com.lowdragmc.lowdraglib2.gui.holder.IModularUIHolder;
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
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Pane;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.api.ui.Windows;
import io.github.izakyl.folkways.front.engine.authority.ColonyAuthority;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.colony.ColonyGround;
import io.github.izakyl.folkways.front.engine.colony.SiteBoard;
import io.github.izakyl.folkways.front.engine.item.ColonyBookItem;
import io.github.izakyl.folkways.front.ui.UiRegistrations;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class ColonyShell
    implements FolkwaysUI, PlayerUIMenuType.PlayerUIHolder, ColonyAuthority.Panel {

    public static final ResourceLocation ID =
        ResourceLocation.fromNamespaceAndPath(FolkwaysMod.MOD_ID, "colony");

    private static final int PANEL_WIDTH = 500;
    private static final int PANEL_HEIGHT = 310;

    private static final int MIN_WIDTH = 320;
    private static final int MIN_HEIGHT = 200;

    private static final int NAV_WIDTH = 104;

    private static final int MARK_WIDTH = 2;
    private static final int MARK_ON = 0xFFE0BC76;

    private static final int WINDOW_WIDTH = 300;
    private static final int WINDOW_HEIGHT = 230;

    private final Optional<UUID> colonyId;
    private final Optional<BlockPos> site;
    private Map<ResourceLocation, Pane> opened = Map.of();
    private ItemListEditor lists;
    private final ZoneSettings selectionSettings = ZoneSettings.create();
    private boolean editingSelection;

    public static void editSelection(Player player, UUID id) {
        of(player).ifPresent(shell -> shell.selectionSettings.hold(id));
    }

    private ColonyShell(Player player) {
        this(player, Optional.empty());
    }

    private ColonyShell(Player player, Optional<BlockPos> at) {
        site = at;
        colonyId = heldColonyId(player).or(() -> siteColonyId(player, at));
    }

    private static Optional<ResourceLocation> answeringPage(Player player, Optional<BlockPos> at) {
        if (!(player instanceof ServerPlayer serverPlayer) || at.isEmpty()) {
            return Optional.empty();
        }
        BlockPos pos = at.get();
        ServerLevel level = serverPlayer.serverLevel();
        return ColonyAuthority.of(serverPlayer, Optional.of(pos))
            .flatMap(authority -> ColonyBoards.firstAt(level, authority.colony(), pos))
            .map(SiteBoard::page);
    }

    private static Optional<UUID> siteColonyId(Player player, Optional<BlockPos> at) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return Optional.empty();
        }
        return at.flatMap(pos -> ColonyGround.ofBlock(serverPlayer.serverLevel(), pos))
            .map(Colony::id);
    }

    public static ModularUI besideContainer(Player player, Optional<BlockPos> at) {
        ColonyShell shell = new ColonyShell(player, at);
        return shell.createSiteUI(player);
    }

    private ModularUI createSiteUI(Player player) {
        Desk desk = new Desk();
        UIElement panel = Rows.page().addClass("panel_bg")
            .layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE)
                .width(WINDOW_WIDTH).height(WINDOW_HEIGHT));
        Tokens.name(panel, "folkways.site.panel");
        panel.setDisplay(false);
        panel.setOverflowVisible(false);
        desk.beside(panel);
        Windows windows = (id, title, body) -> {
            Window window = desk.add(Window.of(id, title,
                0, 0, WINDOW_WIDTH, WINDOW_HEIGHT, 220, 140));
            window.body().addChildren(body);
            return window;
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
        lists = ItemListEditor.create(player);
        lists.livesIn(windows.add("item_list",
            Component.translatable("folkways.settings.items"), lists.build()));
        desk.root().addSyncValue(DataBindingBuilder.stringS2C(() -> answeringPage(player, site)
            .map(ResourceLocation::toString).orElse(""))
            .onSyncReceived(id -> {
                ResourceLocation target = ResourceLocation.tryParse(id);
                panel.setDisplay(target != null && pages.containsKey(target));
                pages.forEach((key, value) -> value.setDisplay(key.equals(target)));
            }).build().getSyncValue());
        return new SiteUI(UI.of(desk.root(),
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    ResourceLocation.fromNamespaceAndPath("folkways", "lss/desk.lss"))),
            screen -> screen), player, this);
    }

    public static final class SiteUI extends ModularUI implements ColonyAuthority.Panel {
        private final ColonyShell shell;

        private SiteUI(UI ui, Player player, ColonyShell shell) {
            super(ui, player);
            this.shell = shell;
        }

        @Override
        public Optional<UUID> colonyId() {
            return shell.colonyId();
        }
    }

    public static void register() {
        PlayerUIMenuType.register(ID, ColonyShell::new);
    }

    @Override
    public Optional<UUID> colonyId() {
        return colonyId;
    }

    static Optional<ColonyShell> of(Player player) {
        return player.containerMenu instanceof ModularUIContainerMenu menu
            && menu.uiHolder instanceof ColonyShell shell
            ? Optional.of(shell)
            : player.containerMenu instanceof IModularUIHolder holder
                && holder.getModularUI() instanceof SiteUI ui
                ? Optional.of(ui.shell) : Optional.empty();
    }

    Optional<ItemListEditor> itemLists() {
        return Optional.ofNullable(lists);
    }

    private static Optional<UUID> heldColonyId(Player player) {
        for (InteractionHand hand : InteractionHand.values()) {
            Optional<UUID> bound = ColonyBookItem.boundColonyId(player.getItemInHand(hand));
            if (bound.isPresent()) {
                return bound;
            }
        }
        return Optional.empty();
    }

    public static void open(ServerPlayer player) {
        PlayerUIMenuType.openUI(player, ID);
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
        Desk desk = new Desk();
        Window panel = desk.panel(Window.of("colony",
            Component.translatable("folkways.panel.title"),
            0f, 0f, PANEL_WIDTH, PANEL_HEIGHT, MIN_WIDTH, MIN_HEIGHT));
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
            entries.addChildren(entry(kind, marks, () -> show(pages, marks, kind.id())));
        }
        mark(marks, listed.get(0).id());
        navList.setDisplay(navList.getChildren().size() > 1);

        UIElement workspace = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(nav, pane);

        UIElement founding = founding(player);
        UIElement configuration = Rows.page();
        configuration.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        Tokens.name(configuration, "folkways.selection.page");
        UIElement draft = Drawing.create().build();
        draft.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        UIElement settings = selectionSettings.build(player);
        settings.setDisplay(false);
        configuration.addChildren(draft, settings);
        configuration.setDisplay(false);
        panel.body().addChildren(founding, workspace, configuration);
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
            workspace.setDisplay(!configuring && !founding.isDisplayed());
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

        lists = ItemListEditor.create(player);
        lists.livesIn(windows.add("item_list",
            Component.translatable("folkways.settings.items"), lists.build()));

        UIElement root = desk.root();
        root.addSyncValue(DataBindingBuilder.boolS2C(() -> Pages.colonyOf(player).isPresent())
            .onSyncReceived(settled -> {
                workspace.setDisplay(settled);
                founding.setDisplay(!settled);
            })
            .build()
            .getSyncValue());
        return new ModularUI(UI.of(root,
            List.of(StylesheetManager.INSTANCE.getStylesheetSafe(StylesheetManager.MC),
                StylesheetManager.INSTANCE.getStylesheetSafe(
                    net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("folkways", "lss/desk.lss"))),
            screen -> screen), player);
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
                    new ColorRectTexture(selected ? 0xFF465950 : 0x00000000)));
            }
            if (selected) {
                bar.getParent().addClass("folkways_selected");
            } else {
                bar.getParent().removeClass("folkways_selected");
            }
        });
    }

    private static UIElement founding(Player player) {
        Button create = new Button()
            .setText(Component.translatable("folkways.action.new_colony"))
            .setOnServerClick(event -> found(player));
        Tokens.name(create, "folkways.colony.create");
        UIElement strip = Rows.page().addChildren(
            Rows.text(Component.translatable("folkways.colony.create")),
            Rows.strip().addChildren(create));
        strip.setDisplay(false);
        return strip;
    }

    private static void found(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        ItemStack book = serverPlayer.getMainHandItem();
        if (book.getItem() instanceof ColonyBookItem && ColonyBookItem.boundColonyId(book).isEmpty()) {
            ColonyBookItem.bindNewColony(book, serverPlayer.serverLevel());
        }
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
