package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEmitter;
import com.lowdragmc.lowdraglib2.gui.sync.rpc.RPCEventBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.ItemDrops;
import io.github.izakyl.folkways.front.api.ui.ItemPicker;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.SignLine;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.Enrollments;
import io.github.izakyl.folkways.front.engine.colony.ColonyBoards;
import io.github.izakyl.folkways.front.engine.colony.ColonySchemas;
import io.github.izakyl.folkways.front.engine.colony.ColonyViews;
import io.github.izakyl.folkways.front.engine.colony.Endorsements;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public final class DomainPage implements Draw {

    private static final int ROW_SLOTS = 32;

    private static final int ACT_SLOTS = Board.Row.MAX_ACTS;

    private static final String FIGURES = "figures";
    private static final String ROWS = "rows";
    private static final String ACTS = "acts";
    private static final String LABEL = "label";
    private static final String VALUE = "value";
    private static final String ICON = "icon";
    private static final String TITLE = "title";
    private static final String DETAIL = "detail";
    private static final String TOLD = "told";
    private static final String KIND = "kind";
    private static final String FOOTER = "footer";
    private static final String TOTAL = "total";
    private static final String TARGET = "target";
    private static final String HEADING = "heading";
    private static final String WHOLE = "whole";
    private static final String LAYOUT = "layout";
    private static final String STYLE = "style";
    private static final String METER = "meter";
    private static final String METER_MAX = "meter_max";
    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String RAW = "raw";
    private static final String SETTING = "setting";
    private static final String CHECKED = "checked";
    private static final String CHOSEN = "chosen";
    private static final String PICKS = "picks";
    private static final String HELD = "held";
    private static final String SINGLE = "single";
    private static final String BLOCKS = "blocks";
    private static final String NAME = "name";

    private static final int KIND_NONE = 0;
    private static final int KIND_DO = 1;
    private static final int KIND_EDIT = 2;
    private static final int KIND_OPEN = 3;
    private static final int KIND_PING = 4;
    private static final int KIND_ADMIT = 5;

    private static final String[] VERBS = {"", "do", "edit", "open", "ping", "admit"};

    private static final int HEADING_COLOR = 0xFF9A3324;
    private static final int TITLE_COLOR = 0xFF2B1C12;
    private static final int METER_FULL = 0xFF5B7F2C;
    private static final int METER_SHORT = 0xFFB0761C;

    // How narrow a title beside pictures may get before the pictures have all given way.
    private static final int TITLE_FLOOR = 40;
    private static final int TOLD_YIELD = 1000;

    @FunctionalInterface
    public interface BlockPinger {
        void ping(BlockPos at);
    }

    private static volatile BlockPinger pinger = at -> {
    };

    private final PageKind page;
    private UIElement figures;

    private DomainPage(PageKind page) {
        this.page = page;
    }

    public static DomainPage of(PageKind page) {
        return new DomainPage(page);
    }

    public static void install(BlockPinger installed) {
        pinger = installed;
    }

    @Override
    public UIElement build(Player player) {
        Drawn drawn = new Drawn();
        figures = Rows.strip();
        figures.layout(layout -> layout.widthAuto().flexShrink(0));
        UIElement grid = Rows.grid();
        drawn.grid = grid;
        drawn.carrying = grid.addRPCEvent(RPCEventBuilder.simple(Integer.class, Integer.class,
            (index, slot) -> {
                if (index >= 0 && index < ROW_SLOTS && slot >= 0 && slot < ACT_SLOTS) {
                    carryOut(player, index, slot);
                }
            }));
        drawn.asking = new ChoicePicker.Asking(grid, (key, option) -> choose(player, key, option));
        drawn.sending = new ItemPicker.Sending(grid, (key, filters) -> hold(player, key, filters));
        for (int index = 0; index < ROW_SLOTS; index++) {
            grid.addChildren(slot(player, drawn, index));
        }
        Label footer = Rows.note(Component.empty());
        drawn.footer = footer;
        drawn.figures = figures;
        drawn.grid = grid;

        ScrollerView scroller = Rows.fills(Rows.box());
        UIElement contents = Rows.page().addChildren(grid, footer);
        page.toolVocation().flatMap(Vocations::of)
            .ifPresent(vocation -> contents.addChildren(SettingsPage.tools(player, vocation, "." + Tokens.of(page.id()))));
        scroller.addScrollViewChild(contents);

        UIElement page = Rows.page().addChildren(scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> boardTag(player))
            .onSyncReceived(tag -> drawn.accept(player, tag))
            .build()
            .getSyncValue());
        return page;
    }

    @Override
    public List<UIElement> actions() {
        return figures == null ? List.of() : List.of(figures);
    }

    private boolean typesNumbers() {
        return scope().map(found -> {
            for (Schema.Setting setting : ColonySchemas.of(found).settings()) {
                if (setting instanceof Schema.Setting.Count) {
                    return true;
                }
            }
            return false;
        }).orElse(false);
    }

    private UIElement slot(Player player, Drawn drawn, int index) {
        Row row = new Row();
        row.icon = Rows.icon(ItemStack.EMPTY);
        row.title = Rows.name(Component.empty());
        row.title.setOverflowVisible(false);
        row.meter = Rows.meter();
        row.meter.setDisplay(false);
        row.detail = Rows.value(Component.empty());
        row.detail.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL));
        row.detail.setOverflowVisible(false);
        row.told = SignLine.of();
        row.told.setDisplay(false);
        row.words = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .alignItems(AlignItems.STRETCH).alignSelf(AlignItems.STRETCH).justifyContent(AlignContent.CENTER)
            .flexGrow(1).flexShrink(1).minWidth(0));
        row.words.setOverflowVisible(false);
        UIElement element = Rows.row().addChildren(row.icon, row.title, row.meter, row.detail, row.told);
        for (int act = 0; act < ACT_SLOTS; act++) {
            int slot = act;
            Button press = Rows.act();
            UIElement shown = Rows.actIcon(ItemStack.EMPTY);
            press.addChildren(shown);
            press.setOnClick(event -> {
                drawn.carrying.send(index, slot);
                switch (drawn.kindAt(index, slot)) {
                    case KIND_OPEN -> drawn.openAt(player, index, slot);
                    case KIND_PING -> drawn.pingAt(index, slot);
                    case KIND_EDIT -> drawn.editAt(player, press, index, slot);
                    default -> {
                    }
                }
            });

            ItemDrops.on(press, new ItemDrops.Drop() {
                @Override
                public boolean accepts(ItemStack stack) {
                    return !stack.isEmpty() && drawn.picks[index][slot] != null;
                }

                @Override
                public void take(ItemStack stack) {
                    drawn.pickAt(index, slot, stack);
                }
            });

            row.editLabels.add(Rows.note(Component.empty()));
            row.editBoxes.add(new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).gapAll(3)));
            row.acts.add(shown);
            row.presses.add(press);
            element.addChildren(press);
            Toggle checkbox = Controls.tick();
            checkbox.setDisplay(false);
            checkbox.setOnToggleChanged(on -> drawn.carrying.send(index, slot));
            checkbox.toggleLabel.addEventListener(UIEvents.MOUSE_DOWN, event -> checkbox.setOn(!checkbox.isOn()));
            row.checkboxes.add(checkbox);
            element.addChildren(checkbox);
            if (drawn.typesNumbers) {
                TextField number = Controls.count(0, Integer.MAX_VALUE);
                number.layout(layout -> layout.height(Rows.ACT_SIZE));
                number.setDisplay(false);
                drawn.typing(number, index, slot, player);
                row.numbers.add(number);
                element.addChildren(number);
            }
        }
        row.controls = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).gapAll(5));
        row.controls.addEventListener(UIEvents.LAYOUT_CHANGED, event -> drawn.sizeControls(row));
        row.element = element;
        row.cell = Rows.cell(element);
        drawn.rows.add(row);
        return row.cell;
    }

    private Optional<ResourceLocation> scope() {
        for (Map.Entry<ResourceLocation, Enrollment> enrolled : Enrollments.all().entrySet()) {
            for (PageKind declared : enrolled.getValue().pages()) {
                if (declared.id().equals(page.id())) {
                    return Optional.of(enrolled.getKey());
                }
            }
        }
        return Optional.empty();
    }

    private Optional<Board> board(Player player) {
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return Optional.empty();
        }
        Optional<BlockPos> at = ColonyShell.siteOf(player);
        return ColonyBoards.answering(colony.get(), page.id(), at,
            ColonyViews.forBoard(colony.get(), level.get(), at));
    }

    private Tag boardTag(Player player) {
        CompoundTag tag = new CompoundTag();
        Optional<Board> board = board(player);
        if (board.isEmpty()) {
            tag.put(ROWS, new ListTag());
            tag.put(FIGURES, new ListTag());
            tag.putInt(TOTAL, 0);
            return tag;
        }
        HolderLookup.Provider registries = player.registryAccess();
        ListTag figures = new ListTag();
        for (Board.Figure figure : board.get().figures()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(LABEL, figure.labelKey());
            entry.put(VALUE, Pages.text(registries, figure.value()));
            figures.add(entry);
        }
        ListTag rows = new ListTag();
        List<Board.Row> all = board.get().rows();
        for (int index = 0; index < Math.min(all.size(), ROW_SLOTS); index++) {
            rows.add(rowTag(player, registries, all.get(index)));
        }
        tag.putString(LAYOUT, board.get().layout().name());
        tag.put(FIGURES, figures);
        tag.put(ROWS, rows);
        tag.putInt(TOTAL, all.size());
        tag.put(FOOTER, Pages.text(registries, footerText(board.get(), all.size())));
        return tag;
    }

    private CompoundTag rowTag(Player player, HolderLookup.Provider registries, Board.Row row) {
        CompoundTag tag = new CompoundTag();
        tag.put(ICON, row.icon().isEmpty() ? new CompoundTag()
            : row.icon().save(registries, new CompoundTag()));
        tag.put(TITLE, Pages.text(registries, row.title()));
        tag.put(DETAIL, Pages.text(registries, row.detail()));
        row.told().ifPresent(told -> tag.put(TOLD, told.save()));
        tag.putBoolean(HEADING, row.heading());
        tag.putBoolean(WHOLE, row.whole());
        tag.putString(STYLE, row.style().name());
        row.meter().ifPresent(meter -> {
            tag.putInt(METER, meter.value());
            tag.putInt(METER_MAX, meter.max());
        });
        ListTag acts = new ListTag();
        for (Board.Act act : row.acts()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt(KIND, kindOf(act));
            entry.put(LABEL, Pages.text(registries, actLabel(player, act)));
            entry.put(ICON, iconOf(act).save(registries, new CompoundTag()));
            if (act instanceof Board.Act.Open open) {
                entry.putString(TARGET, open.page().toString());
            }
            if (act instanceof Board.Act.Edit edit) {
                setting(edit.settingKey()).ifPresent(pair -> {
                    entry.putString(NAME, pair.setting().nameKey());
                    if (pair.setting() instanceof Schema.Setting.Flag flag) {
                        entry.putBoolean(CHECKED, SettingsPage.flagOf(
                            player, new SettingsPage.Scope.AtColony(pair.scope()), flag));
                    }
                    if (pair.setting() instanceof Schema.Setting.Count count) {
                        entry.putString(SETTING, count.key());
                        entry.putString(RAW, Integer.toString(SettingsPage.countOf(
                            player, new SettingsPage.Scope.AtColony(pair.scope()), count)));
                        entry.putInt(MIN, count.min());
                        entry.putInt(MAX, count.max());
                    }
                    if (pair.setting() instanceof Schema.Setting.Choice choice) {
                        entry.putString(SETTING, choice.key());
                        entry.putString(CHOSEN, SettingsPage.choiceOf(
                            player, new SettingsPage.Scope.AtColony(pair.scope()), choice).toString());
                    }
                    if (pair.setting() instanceof Schema.Setting.Items items) {
                        entry.putString(SETTING, items.key());
                        entry.putBoolean(PICKS, true);
                        entry.putString(HELD, ItemPicker.encode(siteItems(player, items.key()).orElseGet(() ->
                            SettingsPage.itemsOf(player, new SettingsPage.Scope.AtColony(pair.scope()), items))));
                        entry.putBoolean(SINGLE, items.single());
                        entry.putBoolean(BLOCKS, items.blocks());
                    }
                });
            }
            if (act instanceof Board.Act.Ping ping) {
                entry.put(TARGET, NbtUtils.writeBlockPos(ping.at()));
            }
            acts.add(entry);
        }
        tag.put(ACTS, acts);
        return tag;
    }

    private Component actLabel(Player player, Board.Act act) {
        return switch (act) {
            case Board.Act.Do carry -> Component.translatable(carry.labelKey());
            case Board.Act.Admit admit -> Component.translatable(admit.labelKey());
            case Board.Act.Open ignored -> Component.translatable("folkways.action.open");
            case Board.Act.Ping ignored -> Component.translatable("folkways.action.ping");
            case Board.Act.Edit edit -> setting(edit.settingKey())
                .map(pair -> SettingsPage.label(player, pair.scope(), pair.setting()))
                .orElseGet(Component::empty);
        };
    }

    private ItemStack iconOf(Board.Act act) {
        return switch (act) {
            case Board.Act.Do carry -> SettingsPage.iconOf(carry.icon());
            case Board.Act.Admit ignored -> SettingsPage.iconOf(vanilla("bell"));
            case Board.Act.Ping ignored -> SettingsPage.iconOf(vanilla("compass"));
            case Board.Act.Open open -> pageIcon(open.page());
            case Board.Act.Edit edit -> setting(edit.settingKey())
                .map(pair -> SettingsPage.iconOf(pair.setting().icon()))
                .orElseGet(() -> SettingsPage.iconOf(vanilla("writable_book")));
        };
    }

    private static ItemStack pageIcon(ResourceLocation target) {
        for (PageKind kind : PageKinds.every()) {
            if (kind.id().equals(target)) {
                return SettingsPage.iconOf(kind.icon());
            }
        }
        return SettingsPage.iconOf(vanilla("book"));
    }

    private static ResourceLocation vanilla(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }

    private static int kindOf(Board.Act act) {
        return switch (act) {
            case Board.Act.Do ignored -> KIND_DO;
            case Board.Act.Admit ignored -> KIND_ADMIT;
            case Board.Act.Edit ignored -> KIND_EDIT;
            case Board.Act.Open ignored -> KIND_OPEN;
            case Board.Act.Ping ignored -> KIND_PING;
        };
    }

    private Component footerText(Board board, int total) {
        if (total == 0) {
            return board.whenEmpty().orElseGet(() -> Component.translatable("folkways.board.empty"));
        }
        if (total > ROW_SLOTS) {
            return Component.translatable("folkways.settings.looks.count", ROW_SLOTS, total);
        }
        return Component.empty();
    }

    private record Declared(ResourceLocation scope, Schema.Setting setting) {
    }

    // The page's own plugin declares most of what it edits; a plugin that draws onto another's page
    // still edits its own settings, so they are looked for there next.
    private Optional<Declared> setting(String key) {
        Optional<ResourceLocation> own = scope();
        Optional<Declared> found = own.flatMap(scope -> declared(scope, key));
        if (found.isPresent()) {
            return found;
        }
        for (ResourceLocation scope : Enrollments.all().keySet()) {
            if (own.filter(scope::equals).isEmpty()) {
                found = declared(scope, key);
                if (found.isPresent()) {
                    return found;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<Declared> declared(ResourceLocation scope, String key) {
        return ColonySchemas.of(scope).find(key).map(setting -> new Declared(scope, setting));
    }

    private void carryOut(Player player, int index, int slot) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        Optional<Board> board = board(player);
        if (colony.isEmpty() || level.isEmpty() || board.isEmpty()) {
            return;
        }
        List<Board.Row> rows = board.get().rows();
        if (index >= rows.size()) {
            return;
        }
        List<Board.Act> acts = rows.get(index).acts();
        if (slot >= acts.size()) {
            return;
        }
        switch (acts.get(slot)) {
            case Board.Act.Do carry -> act(serverPlayer, colony.get(), level.get(), carry.actionKey());
            case Board.Act.Admit admit -> Endorsements
                .called(level.get(), colony.get(), serverPlayer, admit.kind())
                .refusal().ifPresent(why -> serverPlayer.displayClientMessage(why.component(), true));
            case Board.Act.Edit edit -> setting(edit.settingKey()).ifPresent(pair -> {
                SettingsPage.Scope where = new SettingsPage.Scope.AtColony(pair.scope());
                switch (pair.setting()) {
                    case Schema.Setting.Items ignored -> {
                    }
                    case Schema.Setting.Flag flag ->
                        SettingsPage.setFlag(player, where, flag, !SettingsPage.flagOf(player, where, flag));
                    case Schema.Setting.Choice ignored -> {
                    }
                    case Schema.Setting.Count ignored -> {
                    }
                }
            });
            case Board.Act.Open ignored -> {
            }
            case Board.Act.Ping ignored -> {
            }
        }
    }

    private void choose(Player player, String key, ResourceLocation option) {
        setting(key).ifPresent(pair -> {
            SettingsPage.Scope where = new SettingsPage.Scope.AtColony(pair.scope());
            if (pair.setting() instanceof Schema.Setting.Choice choice) {
                SettingsPage.setChoice(player, where, choice, option);
            }
        });
    }

    private Optional<List<ItemFilter>> siteItems(Player player, String key) {
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return Optional.empty();
        }
        Optional<BlockPos> at = ColonyShell.siteOf(player);
        return ColonyBoards.itemFilters(colony.get(), page.id(), at, key,
            ColonyViews.forBoard(colony.get(), level.get(), at));
    }

    private void hold(Player player, String key, List<ItemFilter> filters) {
        setting(key).ifPresent(pair -> {
            if (pair.setting() instanceof Schema.Setting.Items items) {
                List<ItemFilter> kept = SettingsPage.keptItems(items, filters);
                if (items.single() && kept.size() != 1) {
                    return;
                }
                Optional<Colony> colony = Pages.colonyOf(player);
                Optional<ServerLevel> level = Pages.levelOf(player);
                if (player instanceof ServerPlayer serverPlayer && colony.isPresent() && level.isPresent()) {
                    Optional<BlockPos> at = ColonyShell.siteOf(player);
                    Optional<Attempt> applied = ColonyBoards.setItemFilters(colony.get(), page.id(), at, key, kept,
                        ColonyViews.forBoard(colony.get(), level.get(), at));
                    if (applied.isPresent()) {
                        applied.get().refusal().ifPresent(why ->
                            serverPlayer.displayClientMessage(why.component(), true));
                        return;
                    }
                }
                SettingsPage.setItems(player, new SettingsPage.Scope.AtColony(pair.scope()), items, kept);
            }
        });
    }

    private void act(ServerPlayer player, Colony colony, ServerLevel level, String actionKey) {
        ColonyBoards.act(level, colony, page.id(), ColonyShell.siteOf(player), actionKey)
            .flatMap(Attempt::refusal)
            .ifPresent(why -> player.displayClientMessage(why.component(), true));
    }

    private static final class Row {
        private UIElement cell;
        private UIElement element;
        private UIElement icon;
        private Label title;
        private UIElement meter;
        private Label detail;
        private UIElement told;
        // A row that takes a whole line stacks its title over its detail in words in here; any other, and one told in
        // pictures, which stand taller than a line of words, lays them along itself.
        private UIElement words;
        private UIElement controls;
        private boolean formControls;
        private int editorCount;
        private Board.Row.Style presentation = Board.Row.Style.DEFAULT;
        private float editorWidth = -1;
        private final boolean[] counts = new boolean[ACT_SLOTS];
        private final List<Label> editLabels = new ArrayList<>();
        private final List<UIElement> editBoxes = new ArrayList<>();
        private final List<UIElement> acts = new ArrayList<>();
        private final List<Toggle> checkboxes = new ArrayList<>();
        private final List<Button> presses = new ArrayList<>();
        private final List<TextField> numbers = new ArrayList<>();
    }

    private final class Drawn {
        private final boolean typesNumbers = typesNumbers();
        private final List<Row> rows = new ArrayList<>();
        private UIElement figures;
        private UIElement grid;
        private Label footer;
        private boolean form;
        private String structure = "";
        private int[][] kinds = new int[ROW_SLOTS][ACT_SLOTS];
        private ResourceLocation[][] opens = new ResourceLocation[ROW_SLOTS][ACT_SLOTS];
        private BlockPos[][] pings = new BlockPos[ROW_SLOTS][ACT_SLOTS];
        private String[][] choices = new String[ROW_SLOTS][ACT_SLOTS];
        private String[][] picks = new String[ROW_SLOTS][ACT_SLOTS];
        private String[][] held = new String[ROW_SLOTS][ACT_SLOTS];
        private boolean[][] single = new boolean[ROW_SLOTS][ACT_SLOTS];
        private boolean[][] blocks = new boolean[ROW_SLOTS][ACT_SLOTS];
        private String[][] named = new String[ROW_SLOTS][ACT_SLOTS];
        private ItemPicker.Sending sending;
        private RPCEmitter carrying;
        private ResourceLocation[][] chosen = new ResourceLocation[ROW_SLOTS][ACT_SLOTS];
        private ChoicePicker.Asking asking;
        private final String[][] typed = new String[ROW_SLOTS][ACT_SLOTS];
        private final Controls.Echo echoed = new Controls.Echo(ROW_SLOTS * ACT_SLOTS);

        private void typing(TextField number, int index, int slot, Player player) {
            SimpleBinding<String> binding = DataBindingBuilder.stringC2S(
                sealed -> retype(player, index, slot,
                    Controls.stampedKey(sealed), Controls.stampedValue(sealed))).build();
            binding.setRemoteDataSource(IDataSource.of(ignored -> {
            }, () -> Controls.stamped(
                Objects.requireNonNullElse(typed[index][slot], ""), number.getValue())));
            // The form may move its editors, while the grid always stays attached.
            grid.addSyncValue(binding.getSyncValue());
        }

        private void retype(Player player, int index, int slot, String key, String value) {
            if (key.isEmpty()) {
                return;
            }
            Optional<Board> board = board(player);
            if (board.isEmpty() || index >= board.get().rows().size()) {
                return;
            }
            List<Board.Act> acts = board.get().rows().get(index).acts();
            if (slot >= acts.size() || !(acts.get(slot) instanceof Board.Act.Edit edit)
                    || !edit.settingKey().equals(key)) {
                return;
            }
            setting(key).ifPresent(pair -> {
                if (pair.setting() instanceof Schema.Setting.Count count) {
                    SettingsPage.setCount(player,
                        new SettingsPage.Scope.AtColony(pair.scope()), count, value);
                }
            });
        }

        private int kindAt(int index, int slot) {
            return kinds[index][slot];
        }

        private void editAt(Player player, Button press, int index, int slot) {
            String picking = picks[index][slot];
            if (picking != null) {
                SettingsPage.openItems(press, Component.translatable(named[index][slot]), single[index][slot],
                    blocks[index][slot], ItemPicker.decode(held[index][slot]),
                    filters -> sending.send(picking, filters));
            }
            String choosing = choices[index][slot];
            if (choosing != null) {
                setting(choosing).ifPresent(pair -> {
                    if (pair.setting() instanceof Schema.Setting.Choice choice) {
                        ChoicePicker.open(press, choice, chosen[index][slot],
                            option -> asking.ask(choice.key(), option));
                    }
                });
            }
        }

        // An item dragged onto the button from a recipe viewer: the one named, or one more rule.
        private void pickAt(int index, int slot, ItemStack stack) {
            String picking = picks[index][slot];
            if (picking == null || stack.isEmpty()) {
                return;
            }
            if (single[index][slot]) {
                sending.send(picking, List.of(ItemFilter.item(BuiltInRegistries.ITEM.getKey(stack.getItem()))));
                return;
            }
            FilterStacks.parse(stack).ifPresent(dropped -> {
                List<ItemFilter> rules = new ArrayList<>(ItemPicker.decode(held[index][slot]));
                if (!rules.contains(dropped)) {
                    rules.add(dropped);
                    sending.send(picking, rules);
                }
            });
        }

        private void openAt(Player player, int index, int slot) {
            ResourceLocation target = opens[index][slot];
            if (target != null) {
                ColonyShell.openPage(player, target);
            }
        }

        private void pingAt(int index, int slot) {
            BlockPos target = pings[index][slot];
            if (target != null) {
                pinger.ping(target);
            }
        }

        private void accept(Player player, Tag tag) {
            if (!(tag instanceof CompoundTag board)) {
                return;
            }
            HolderLookup.Provider registries = player.registryAccess();
            acceptFigures(registries, board.getList(FIGURES, Tag.TAG_COMPOUND));
            ListTag listed = board.getList(ROWS, Tag.TAG_COMPOUND);
            grid.setDisplay(!listed.isEmpty());
            form = Board.Layout.FORM.name().equals(board.getString(LAYOUT));
            arrange(listed);
            acceptRows(registries, listed);
            footer.setText(board.contains(FOOTER)
                ? Pages.readText(registries, board.get(FOOTER)) : Component.empty());
        }

        private void acceptFigures(HolderLookup.Provider registries, ListTag list) {
            figures.clearAllChildren();
            figures.setDisplay(!list.isEmpty());
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                figures.addChildren(Rows.figure(Component.translatable(entry.getString(LABEL)),
                    Pages.readText(registries, entry.get(VALUE))));
            }
        }

        // Only regroup when structure changes; live count updates retain their editor and focus.
        private void arrange(ListTag list) {
            StringBuilder shape = new StringBuilder(Boolean.toString(form));
            for (int index = 0; index < list.size(); index++) {
                CompoundTag entry = list.getCompound(index);
                shape.append(':').append(entry.getBoolean(HEADING)).append(entry.getString(STYLE));
            }
            if (structure.equals(shape.toString())) {
                return;
            }
            structure = shape.toString();
            for (Row row : rows) {
                if (row.cell.getParent() != null) {
                    row.cell.getParent().removeChild(row.cell);
                }
            }
            grid.clearAllChildren();
            grid.layout(layout -> layout.flexDirection(form ? FlexDirection.COLUMN : FlexDirection.ROW)
                .gapAll(form ? 4 : 0));
            UIElement section = null;
            UIElement sectionRows = null;
            UIElement top = Rows.strip();
            top.layout(layout -> layout.alignItems(AlignItems.STRETCH).gapAll(0).flexShrink(0));
            top.addEventListener(UIEvents.LAYOUT_CHANGED, event -> {
                boolean narrow = top.getSizeWidth() < 300;
                if (narrow == top.hasClass("folkways_stacked_sections")) {
                    return;
                }
                if (narrow) {
                    top.addClass("folkways_stacked_sections");
                } else {
                    top.removeClass("folkways_stacked_sections");
                }
                top.layout(layout -> layout.flexDirection(narrow ? FlexDirection.COLUMN : FlexDirection.ROW));
                for (int at = 0; at < top.getChildren().size(); at++) {
                    boolean first = at == 0;
                    top.getChildren().get(at).layout(layout -> layout.widthPercent(narrow ? 100 : first ? 33 : 67)
                        .paddingRight(!narrow && first ? 2 : 0).paddingLeft(!narrow && !first ? 2 : 0)
                        .paddingBottom(narrow && first ? 4 : 0));
                }
            });
            int sections = 0;
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                if (!form || index >= list.size()) {
                    grid.addChildren(row.cell);
                    continue;
                }
                boolean heading = list.getCompound(index).getBoolean(HEADING);
                if (section == null || heading) {
                    section = Rows.page().addClass("folkways_form_section");
                    section.layout(layout -> layout.paddingHorizontal(4).paddingVertical(4).gapAll(2).flexShrink(0));
                    sectionRows = Rows.grid(270);
                    if (sections < 2) {
                        if (sections == 0) {
                            grid.addChildren(top);
                        }
                        boolean first = sections == 0;
                        section.layout(layout -> layout.flexGrow(1));
                        if (first) {
                            sectionRows.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                                .flexWrap(FlexWrap.NO_WRAP));
                        }
                        UIElement column = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                            .widthPercent(first ? 33 : 67).flexShrink(0).minWidth(0)
                            .paddingRight(first ? 2 : 0).paddingLeft(first ? 0 : 2));
                        column.addChildren(section);
                        top.addChildren(column);
                    } else {
                        grid.addChildren(section);
                    }
                    sections++;
                    if (heading) {
                        section.addChildren(row.cell, sectionRows);
                        continue;
                    }
                    section.addChildren(sectionRows);
                }
                sectionRows.addChildren(row.cell);
            }
        }

        private void acceptRows(HolderLookup.Provider registries, ListTag list) {
            boolean right = false;
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                if (index >= list.size()) {
                    row.cell.setDisplay(false);
                    Tokens.name(row.element, Tokens.NONE);
                    for (Button press : row.presses) {
                        Tokens.name(press, Tokens.NONE);
                    }
                    continue;
                }
                CompoundTag entry = list.getCompound(index);
                row.cell.setDisplay(true);
                Tokens.name(row.element, "folkways.board.row." + Tokens.of(page.id()) + "." + index);
                Component title = Pages.readText(registries, entry.get(TITLE));
                row.title.setText(title);
                row.detail.setText(Pages.readText(registries, entry.get(DETAIL)));
                boolean heading = entry.getBoolean(HEADING);
                Board.Row.Style presentation = rowStyle(entry);
                if (row.formControls && (!form || heading || presentation == Board.Row.Style.DEFAULT)) {
                    restoreRow(row);
                }
                acceptHeading(row, heading);
                if (form && heading) {
                    row.element.layout(layout -> layout.height(16));
                    row.cell.layout(layout -> layout.paddingBottom(1));
                } else if (form && presentation != Board.Row.Style.DEFAULT) {
                    row.cell.layout(layout -> layout.paddingBottom(2));
                } else {
                    row.element.layout(layout -> layout.height(Rows.ROW_HEIGHT));
                    row.cell.layout(layout -> layout.paddingBottom(Rows.GAP));
                }
                acceptTold(row, entry, heading);
                if (!row.formControls) {
                    boolean hasCheckbox = entry.getList(ACTS, Tag.TAG_COMPOUND).stream()
                        .anyMatch(act -> ((CompoundTag) act).contains(CHECKED));
                    acceptStacked(row, !heading && entry.getBoolean(WHOLE) && !entry.contains(TOLD) && !hasCheckbox);
                }
                acceptIcon(registries, row, entry.getCompound(ICON));
                acceptMeter(row, entry);
                ListTag acts = entry.getList(ACTS, Tag.TAG_COMPOUND);
                acceptActs(registries, row, index, acts, form && presentation != Board.Row.Style.DEFAULT,
                    !title.getString().isBlank());
                if (form) {
                    acceptForm(row, presentation, heading, acts.size());
                }
                boolean toggle = presentation == Board.Row.Style.ON || presentation == Board.Row.Style.OFF;
                boolean wide = form ? !toggle : heading || entry.getBoolean(WHOLE) || acts.size() > 2
                    || row.numbers.stream().anyMatch(UIElement::isDisplayed);
                if (heading) {
                    right = false;
                }
                Rows.Span span = form && toggle ? Rows.Span.CONTENT
                    : wide ? Rows.Span.WHOLE : right ? Rows.Span.RIGHT : Rows.Span.LEFT;
                Rows.place(row.cell, span);
                right = span == Rows.Span.LEFT;
            }
        }

        private Board.Row.Style rowStyle(CompoundTag entry) {
            try {
                return Board.Row.Style.valueOf(entry.getString(STYLE));
            } catch (IllegalArgumentException ignored) {
                return Board.Row.Style.DEFAULT;
            }
        }

        private void restoreRow(Row row) {
            row.element.removeChild(row.controls);
            for (int slot = 0; slot < ACT_SLOTS; slot++) {
                UIElement box = row.editBoxes.get(slot);
                Button press = row.presses.get(slot);
                box.removeChild(press);
                row.element.addChildren(press);
                press.noText();
                press.layout(layout -> layout.width(Rows.ACT_SIZE).height(Rows.ACT_SIZE).paddingAll(0));
                if (!row.numbers.isEmpty()) {
                    TextField number = row.numbers.get(slot);
                    box.removeChild(number);
                    row.element.addChildren(number);
                    number.layout(layout -> layout.width(50));
                }
            }
            row.controls.clearAllChildren();
            for (String style : List.of("folkways_form_toggle", "folkways_form_on", "folkways_form_off")) {
                row.element.removeClass(style);
            }
            row.element.layout(layout -> layout.flexDirection(FlexDirection.ROW).height(Rows.ROW_HEIGHT)
                .alignItems(AlignItems.CENTER).gapAll(Rows.GAP).paddingVertical(0).paddingHorizontal(Rows.GAP));
            row.title.setDisplay(true);
            row.title.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveHeight(false));
            row.detail.layout(layout -> layout.heightAuto());
            row.detail.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveWidth(true)
                .adaptiveHeight(false).textColor(Rows.INK));
            row.element.layout(layout -> layout.widthPercent(100));
            row.formControls = false;
            row.editorWidth = -1;
        }

        private void acceptForm(Row row, Board.Row.Style presentation, boolean heading, int acts) {
            boolean custom = !heading && presentation != Board.Row.Style.DEFAULT;
            if (!custom) {
                return;
            }
            boolean toggle = presentation == Board.Row.Style.ON || presentation == Board.Row.Style.OFF;
            boolean note = presentation == Board.Row.Style.NOTE;
            row.element.removeClass("folkways_card");
            for (String style : List.of("folkways_form_toggle", "folkways_form_on", "folkways_form_off")) {
                row.element.removeClass(style);
            }
            if (toggle) {
                row.element.addClass("folkways_form_toggle");
                row.element.addClass(presentation == Board.Row.Style.ON ? "folkways_form_on" : "folkways_form_off");
            }
            row.icon.setDisplay(false);
            row.element.layout(layout -> layout.flexDirection(FlexDirection.COLUMN).heightAuto()
                .alignItems(AlignItems.STRETCH).gapAll(2).paddingHorizontal(0).paddingVertical(0));
            if (!row.formControls) {
                // Keep the original bound widgets; moving them never changes their action indices.
                if (row.words.getParent() == row.element) {
                    row.element.removeChild(row.words);
                }
                for (UIElement part : List.of(row.title, row.meter, row.detail, row.told)) {
                    if (part.getParent() != null) {
                        part.getParent().removeChild(part);
                    }
                    row.element.addChildren(part);
                }
                for (int slot = 0; slot < ACT_SLOTS; slot++) {
                    Button press = row.presses.get(slot);
                    if (press.getParent() != null) {
                        press.getParent().removeChild(press);
                    }
                    UIElement box = row.editBoxes.get(slot);
                    if (row.editLabels.get(slot).getParent() == null) {
                        box.addChildren(row.editLabels.get(slot));
                    }
                    box.addChildren(press);
                    if (!row.numbers.isEmpty()) {
                        TextField number = row.numbers.get(slot);
                        if (number.getParent() != null) {
                            number.getParent().removeChild(number);
                        }
                        box.addChildren(number);
                    }
                    row.controls.addChildren(box);
                }
                row.element.addChildren(row.controls);
                row.formControls = true;
            }
            row.title.layout(layout -> layout.widthPercent(100).flexGrow(0).flexShrink(0).minWidth(0));
            row.title.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveHeight(true));
            row.title.setDisplay(note && !row.title.getText().getString().isEmpty());
            row.detail.layout(layout -> layout.widthPercent(100).flexShrink(0));
            row.detail.textStyle(style -> style.textWrap(TextWrap.WRAP).adaptiveWidth(false).adaptiveHeight(true)
                .textColor(Rows.QUIET));
            boolean pictorial = !toggle && row.told.isDisplayed();
            row.detail.setDisplay(!toggle && !pictorial && !row.detail.getText().getString().isEmpty());
            row.detail.textStyle(style -> style.textWrap(TextWrap.HOVER_ROLL).adaptiveHeight(false));
            row.detail.layout(layout -> layout.height(12));
            row.told.layout(layout -> layout.widthPercent(100).flexGrow(0).flexShrink(0));
            row.told.setDisplay(pictorial);
            row.controls.setDisplay(!note && acts > 0);
            row.controls.layout(layout -> layout.flexWrap(FlexWrap.NO_WRAP));
            row.editorCount = acts;
            row.presentation = presentation;
            if (!toggle) {
                row.element.layout(layout -> layout.widthPercent(100));
            }
            for (int slot = 0; slot < ACT_SLOTS; slot++) {
                UIElement box = row.editBoxes.get(slot);
                box.layout(layout -> layout.flexGrow(0).flexShrink(0).gapAll(1));
                row.editLabels.get(slot).setDisplay(presentation == Board.Row.Style.FIELD && acts > 1 && slot < acts);
                row.editLabels.get(slot).layout(layout -> layout.minHeight(11).height(11).flexShrink(0));
                row.presses.get(slot).layout(layout -> layout.height(Rows.ACT_SIZE)
                    .paddingHorizontal(4));
            }
            boolean singleField = presentation == Board.Row.Style.FIELD && acts == 1
                && (!row.detail.getText().getString().isEmpty() || pictorial);
            if (singleField) {
                row.element.layout(layout -> layout.flexDirection(FlexDirection.ROW).alignItems(AlignItems.CENTER));
                row.detail.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1).minWidth(0));
                row.told.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1).minWidth(0));
            }
            row.controls.layout(layout -> layout.flexGrow(0).flexShrink(0).minWidth(0));
            if (presentation == Board.Row.Style.FIELD && acts > 1) {
                row.detail.setDisplay(false);
            }
            row.editorWidth = -1;
            sizeControls(row);
        }

        // Controls keep the width their content needs; pixels avoid zero-basis percentage resolution in Taffy.
        private void sizeControls(Row row) {
            if (!row.formControls || row.editorCount <= 0) {
                return;
            }
            boolean field = row.presentation == Board.Row.Style.FIELD;
            boolean toggle = row.presentation == Board.Row.Style.ON || row.presentation == Board.Row.Style.OFF;
            float room = row.element.getContentWidth();
            float[] widths = new float[row.editorCount];
            float total = 5 * (row.editorCount - 1);
            for (int slot = 0; slot < row.editorCount; slot++) {
                int text = Minecraft.getInstance().font.width(row.presses.get(slot).text.getText());
                widths[slot] = row.counts[slot] ? 56 : field ? row.editorCount == 1 ? 72 : 96
                    : Math.max(40, Math.min(toggle ? 80 : 100, text + 12));
                total += widths[slot];
            }
            float scale = field && room > 0 && total > room
                ? Math.max(0.1f, (room - 5 * (row.editorCount - 1)) / (total - 5 * (row.editorCount - 1))) : 1;
            float used = 5 * (row.editorCount - 1);
            for (float width : widths) {
                used += width * scale;
            }
            if (Math.abs(used - row.editorWidth) < 0.01f) {
                return;
            }
            row.editorWidth = used;
            float strip = used;
            row.controls.layout(layout -> layout.width(strip));
            if (toggle) {
                row.element.layout(layout -> layout.width(strip));
            }
            for (int slot = 0; slot < row.editorCount; slot++) {
                float width = widths[slot] * scale;
                row.editBoxes.get(slot).layout(layout -> layout.width(width).flexGrow(0).flexShrink(0));
                row.editLabels.get(slot).layout(layout -> layout.width(width));
                row.presses.get(slot).layout(layout -> layout.width(width));
                row.presses.get(slot).text.layout(layout -> layout.width(Math.max(1, width - 12)));
                row.presses.get(slot).textStyle(style -> style.adaptiveWidth(false).textWrap(TextWrap.HOVER_ROLL));
                if (!row.numbers.isEmpty()) {
                    row.numbers.get(slot).layout(layout -> layout.width(width));
                }
            }
        }

        // A heading row spans the grid as a bare section label; every other row is a card.
        private void acceptHeading(Row row, boolean heading) {
            if (heading) {
                row.element.removeClass("folkways_card");
            } else {
                row.element.addClass("folkways_card");
            }
            row.icon.setDisplay(!heading);
            row.detail.setDisplay(!heading);
            row.title.textStyle(style -> style.textColor(heading ? HEADING_COLOR : TITLE_COLOR));
            if (heading) {
                row.meter.setDisplay(false);
                for (Button press : row.presses) {
                    press.setDisplay(false);
                }
            }
        }

        // A detail told in pictures is drawn in place of its words; short of room, the pictures give way, not the title.
        // A title that only grows starts from nothing, and a detail or pictures as wide as the room leave it none; so
        // beside either it takes its own width. Pictures take what room is left and shrink long before it does; a
        // detail in words gives way alongside it, each rolling on hover what it no longer has room to show.
        private void acceptTold(Row row, CompoundTag entry, boolean heading) {
            boolean told = !heading && entry.contains(TOLD);
            row.told.setDisplay(told);
            int width = Minecraft.getInstance().font.width(row.title.getText());
            if (heading) {
                row.title.layout(layout -> layout.widthAuto().flexGrow(1).flexShrink(1).minWidth(0));
            } else if (told) {
                row.title.layout(layout -> layout.width(width).flexGrow(0).flexShrink(1)
                    .minWidth(Math.min(width, TITLE_FLOOR)));
                row.told.layout(layout -> layout.flexGrow(1).flexShrink(TOLD_YIELD));
            } else {
                row.title.layout(layout -> layout.width(width).flexGrow(1).flexShrink(1)
                    .minWidth(Math.min(width, TITLE_FLOOR)));
            }
            row.detail.setDisplay(!heading && !told);
            if (told) {
                SignLine.show(row.told, Sentence.load(entry.getList(TOLD, Tag.TAG_COMPOUND)));
            }
        }

        // A slot is laid again for whatever row it shows next, so its words move into the stack or back out of it.
        // Only the stack holds them a level down: a part that grows inside a box that itself grows is laid at nothing.
        private void acceptStacked(Row row, boolean stacked) {
            List<UIElement> parts = List.of(row.title, row.meter, row.detail, row.told);
            boolean held = row.words.getParent() == row.element;
            if (stacked && !held) {
                for (UIElement part : parts) {
                    row.element.removeChild(part);
                }
                row.words.addChildren(parts.toArray(UIElement[]::new));
                row.element.addChildAt(row.words, 1);
            } else if (!stacked && held) {
                row.element.removeChild(row.words);
                for (int at = 0; at < parts.size(); at++) {
                    row.words.removeChild(parts.get(at));
                    row.element.addChildAt(parts.get(at), 1 + at);
                }
            }
            if (stacked) {
                row.title.layout(layout -> layout.widthAuto().flexGrow(0).flexShrink(0).minWidth(0));
                row.detail.layout(layout -> layout.flexShrink(0));
                row.told.layout(layout -> layout.flexGrow(0).flexShrink(0));
            } else {
                row.detail.layout(layout -> layout.widthAuto().flexShrink(1));
            }
        }

        private void acceptMeter(Row row, CompoundTag entry) {
            if (!entry.contains(METER_MAX) || entry.getBoolean(HEADING)) {
                row.meter.setDisplay(false);
                return;
            }
            Board.Meter meter = new Board.Meter(entry.getInt(METER), entry.getInt(METER_MAX));
            row.meter.setDisplay(true);
            Rows.meterFill(row.meter, meter.percent(), meter.met() ? METER_FULL : METER_SHORT);
        }

        private void acceptIcon(HolderLookup.Provider registries, Row row, CompoundTag saved) {
            ItemStack stack = saved.isEmpty() ? ItemStack.EMPTY
                : ItemStack.parse(registries, saved).orElse(ItemStack.EMPTY);
            row.icon.style(style -> style.background(new ItemStackTexture(stack)));
        }

        private void acceptActs(HolderLookup.Provider registries, Row row, int index, ListTag list,
                boolean readable, boolean titled) {
            for (int slot = 0; slot < row.presses.size(); slot++) {
                TextField number = row.numbers.isEmpty() ? null : row.numbers.get(slot);
                if (slot >= list.size()) {
                    row.counts[slot] = false;
                    row.editBoxes.get(slot).setDisplay(false);
                    row.presses.get(slot).setDisplay(false);
                    row.checkboxes.get(slot).setDisplay(false);
                    Tokens.name(row.checkboxes.get(slot), Tokens.NONE);
                    Tokens.name(row.presses.get(slot), Tokens.NONE);
                    if (number != null) {
                        number.setDisplay(false);
                        Tokens.name(number, Tokens.NONE);
                    }
                    kinds[index][slot] = KIND_NONE;
                    opens[index][slot] = null;
                    pings[index][slot] = null;
                    choices[index][slot] = null;
                    chosen[index][slot] = null;
                    picks[index][slot] = null;
                    held[index][slot] = null;
                    forget(index, slot);
                    continue;
                }
                CompoundTag entry = list.getCompound(slot);
                Button press = row.presses.get(slot);
                Component label = Pages.readText(registries, entry.get(LABEL));
                Component caption = entry.getString(NAME).isEmpty()
                    ? label : Component.translatable(entry.getString(NAME));
                row.editLabels.get(slot).setText(caption);
                row.editBoxes.get(slot).setDisplay(true);
                row.acts.get(slot).setDisplay(!readable && !entry.contains(CHOSEN));
                if (readable || entry.contains(CHOSEN)) {
                    boolean multipleItems = entry.getBoolean(PICKS) && !entry.getBoolean(SINGLE);
                    Component readableLabel = multipleItems || label.getString().isBlank() ? caption : label;
                    press.enableText().setText(readableLabel);
                    if (!readable) {
                        int width = Minecraft.getInstance().font.width(readableLabel) + 12;
                        press.layout(layout -> layout.width(width).minWidth(SettingsPage.CONTROL_MIN)
                            .flexShrink(1).paddingHorizontal(4));
                        press.text.layout(layout -> layout.width(Math.max(1, width - 12)));
                        press.textStyle(style -> style.adaptiveWidth(false).textWrap(TextWrap.HOVER_ROLL));
                    }
                } else {
                    press.noText();
                }
                ItemStack icon = ItemStack.parse(registries, entry.getCompound(ICON))
                    .orElse(ItemStack.EMPTY);
                row.acts.get(slot).style(style -> style.background(new ItemStackTexture(icon)));
                kinds[index][slot] = entry.getInt(KIND);
                opens[index][slot] = kinds[index][slot] == KIND_OPEN
                    ? ResourceLocation.tryParse(entry.getString(TARGET)) : null;
                pings[index][slot] = kinds[index][slot] == KIND_PING
                    ? NbtUtils.readBlockPos(entry, TARGET).orElse(null) : null;
                boolean choosing = kinds[index][slot] == KIND_EDIT && entry.contains(CHOSEN);
                choices[index][slot] = choosing ? entry.getString(SETTING) : null;
                chosen[index][slot] = choosing ? ResourceLocation.tryParse(entry.getString(CHOSEN)) : null;
                picks[index][slot] = kinds[index][slot] == KIND_EDIT && entry.getBoolean(PICKS)
                    ? entry.getString(SETTING) : null;
                held[index][slot] = entry.getString(HELD);
                single[index][slot] = entry.getBoolean(SINGLE);
                blocks[index][slot] = entry.getBoolean(BLOCKS);
                named[index][slot] = entry.getString(NAME);

                boolean counts = number != null && kinds[index][slot] == KIND_EDIT
                    && entry.contains(RAW);
                row.counts[slot] = counts;
                boolean flag = entry.contains(CHECKED) && !readable;
                Toggle checkbox = row.checkboxes.get(slot);
                checkbox.setDisplay(flag);
                if (flag && titled) {
                    // The row's own title already names the flag; the box alone sits where an act would.
                    checkbox.noText();
                    checkbox.layout(layout -> layout.widthAuto().flexShrink(0));
                    checkbox.setOn(entry.getBoolean(CHECKED), false);
                } else if (flag) {
                    checkbox.enableText().setText(caption);
                    int width = Minecraft.getInstance().font.width(caption) + 22;
                    // A narrow column rolls the caption rather than pushing the box out of the row.
                    checkbox.layout(layout -> layout.width(width).flexShrink(1).minWidth(Rows.ACT_SIZE));
                    checkbox.toggleLabel.textStyle(style -> style.adaptiveWidth(false).textWrap(TextWrap.HOVER_ROLL));
                    checkbox.setOn(entry.getBoolean(CHECKED), false);
                }
                Tokens.name(checkbox, flag ? token(kinds[index][slot], index, slot) : Tokens.NONE);
                press.setDisplay(!counts && !flag);
                Component tooltip = label.getString().isBlank() ? caption : label;
                press.style(style -> style.tooltips(tooltip.getString().isBlank()
                    ? new Component[0] : new Component[] {tooltip}));

                Tokens.name(press, counts || flag ? Tokens.NONE : token(kinds[index][slot], index, slot));
                if (number != null) {
                    number.setDisplay(counts);
                    Tokens.name(number, counts ? token(kinds[index][slot], index, slot) : Tokens.NONE);
                }
                if (!counts) {
                    forget(index, slot);
                    continue;
                }
                accept(number, index, slot, entry);
            }
        }

        private void accept(TextField number, int index, int slot, CompoundTag entry) {
            String key = entry.getString(SETTING);
            if (!key.equals(typed[index][slot])) {
                typed[index][slot] = key;
                echoed.forget(index * ACT_SLOTS + slot);
                Controls.ranged(number, entry.getInt(MIN), entry.getInt(MAX));
            }
            if (!form) {
                Controls.ranged(number, entry.getInt(MIN), entry.getInt(MAX));
            }
            String raw = entry.getString(RAW);
            if (echoed.fresh(index * ACT_SLOTS + slot, raw)) {
                number.setValue(raw, false);
            }
        }

        private void forget(int index, int slot) {
            typed[index][slot] = null;
            echoed.forget(index * ACT_SLOTS + slot);
        }

        private String token(int kind, int index, int slot) {
            return kind <= KIND_NONE || kind >= VERBS.length
                ? Tokens.NONE
                : "folkways.board." + VERBS[kind] + "." + Tokens.of(page.id()) + "." + index + "." + slot;
        }
    }
}
