package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.Enrollment;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.notice.Attempt;
import io.github.izakyl.folkways.front.api.notice.Sentence;
import io.github.izakyl.folkways.front.api.panel.Board;
import io.github.izakyl.folkways.front.api.panel.PageKind;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Draw;
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
    private static final String METER = "meter";
    private static final String METER_MAX = "meter_max";
    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String RAW = "raw";
    private static final String SETTING = "setting";
    private static final String CHOSEN = "chosen";

    private static final int KIND_NONE = 0;
    private static final int KIND_DO = 1;
    private static final int KIND_EDIT = 2;
    private static final int KIND_OPEN = 3;
    private static final int KIND_PING = 4;
    private static final int KIND_ADMIT = 5;

    private static final String[] VERBS = {"", "do", "edit", "open", "ping", "admit"};

    private static final int HEADING_COLOR = 0xFFE0BC76;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int METER_FULL = 0xFF4C9A2A;
    private static final int METER_SHORT = 0xFFB58900;

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
        drawn.asking = new ChoicePicker.Asking(grid, (key, option) -> choose(player, key, option));
        for (int index = 0; index < ROW_SLOTS; index++) {
            grid.addChildren(slot(player, drawn, index));
        }
        Label footer = Rows.note(Component.empty());
        drawn.footer = footer;
        drawn.figures = figures;
        drawn.grid = grid;

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(grid, footer));

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
        row.meter = Rows.meter();
        row.meter.setDisplay(false);
        row.detail = Rows.value(Component.empty());
        row.told = SignLine.of();
        row.told.setDisplay(false);
        UIElement element = Rows.row().addChildren(row.icon, row.title, row.meter, row.detail, row.told);
        for (int act = 0; act < ACT_SLOTS; act++) {
            int slot = act;
            Button press = Rows.act();
            UIElement shown = Rows.actIcon(ItemStack.EMPTY);
            press.addChildren(shown);
            press.setOnServerClick(event -> carryOut(player, index, slot));
            press.setOnClick(event -> {
                switch (drawn.kindAt(index, slot)) {
                    case KIND_OPEN -> drawn.openAt(player, index, slot);
                    case KIND_PING -> drawn.pingAt(index, slot);
                    case KIND_EDIT -> drawn.editAt(player, press, index, slot);
                    default -> {
                    }
                }
            });

            row.acts.add(shown);
            row.presses.add(press);
            element.addChildren(press);
            if (drawn.typesNumbers) {
                TextField number = Controls.count(0, Integer.MAX_VALUE);
                number.layout(layout -> layout.height(Rows.ACT_SIZE));
                number.setDisplay(false);
                drawn.typing(number, index, slot, player);
                row.numbers.add(number);
                element.addChildren(number);
            }
        }
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
            if (act instanceof Board.Act.Edit edit && itemList(edit.settingKey())) {
                entry.putString(TARGET, edit.settingKey());
            }
            if (act instanceof Board.Act.Edit edit) {
                setting(edit.settingKey()).ifPresent(pair -> {
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
            case Board.Act.Do ignored -> SettingsPage.iconOf(vanilla("lever"));
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

    private boolean itemList(String key) {
        return setting(key).filter(pair -> pair.setting() instanceof Schema.Setting.Items).isPresent();
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
                    case Schema.Setting.Items items -> ItemListEditor.open(player, pair.scope(), items.key());
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
            if (pair.setting() instanceof Schema.Setting.Choice choice) {
                SettingsPage.setChoice(player, new SettingsPage.Scope.AtColony(pair.scope()), choice, option);
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
        private final List<UIElement> acts = new ArrayList<>();
        private final List<Button> presses = new ArrayList<>();
        private final List<TextField> numbers = new ArrayList<>();
    }

    private final class Drawn {
        private final boolean typesNumbers = typesNumbers();
        private final List<Row> rows = new ArrayList<>();
        private UIElement figures;
        private UIElement grid;
        private Label footer;
        private int[][] kinds = new int[ROW_SLOTS][ACT_SLOTS];
        private ResourceLocation[][] opens = new ResourceLocation[ROW_SLOTS][ACT_SLOTS];
        private BlockPos[][] pings = new BlockPos[ROW_SLOTS][ACT_SLOTS];
        private String[][] lists = new String[ROW_SLOTS][ACT_SLOTS];
        private String[][] choices = new String[ROW_SLOTS][ACT_SLOTS];
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
            number.addSyncValue(binding.getSyncValue());
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
            String key = lists[index][slot];
            if (key != null) {
                setting(key).ifPresent(pair -> ItemListEditor.open(player, pair.scope(), key));
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
                row.title.setText(Pages.readText(registries, entry.get(TITLE)));
                row.detail.setText(Pages.readText(registries, entry.get(DETAIL)));
                boolean heading = entry.getBoolean(HEADING);
                acceptHeading(row, heading);
                acceptTold(row, entry, heading);
                acceptIcon(registries, row, entry.getCompound(ICON));
                acceptMeter(row, entry);
                ListTag acts = entry.getList(ACTS, Tag.TAG_COMPOUND);
                acceptActs(registries, row, index, acts);
                boolean wide = heading || acts.size() > 2
                    || row.numbers.stream().anyMatch(UIElement::isDisplayed);
                Rows.Span span = wide ? Rows.Span.WHOLE : right ? Rows.Span.RIGHT : Rows.Span.LEFT;
                Rows.place(row.cell, span);
                right = span == Rows.Span.LEFT;
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
        // A title that only grows starts from nothing, and pictures as wide as the room leave it none; so beside
        // pictures it takes its own width, the pictures take what room is left, and they shrink long before it does.
        private void acceptTold(Row row, CompoundTag entry, boolean heading) {
            boolean told = !heading && entry.contains(TOLD);
            row.told.setDisplay(told);
            if (told) {
                int width = Minecraft.getInstance().font.width(row.title.getText());
                row.title.layout(layout -> layout.width(width).flexGrow(0).flexShrink(1)
                    .minWidth(Math.min(width, TITLE_FLOOR)));
                row.told.layout(layout -> layout.flexGrow(1).flexShrink(TOLD_YIELD));
            } else {
                row.title.layout(layout -> layout.widthAuto().flexGrow(1).flexShrink(1).minWidth(0));
            }
            row.detail.setDisplay(!heading && !told);
            if (told) {
                SignLine.show(row.told, Sentence.load(entry.getList(TOLD, Tag.TAG_COMPOUND)));
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

        private void acceptActs(HolderLookup.Provider registries, Row row, int index, ListTag list) {
            for (int slot = 0; slot < row.presses.size(); slot++) {
                TextField number = row.numbers.isEmpty() ? null : row.numbers.get(slot);
                if (slot >= list.size()) {
                    row.presses.get(slot).setDisplay(false);
                    Tokens.name(row.presses.get(slot), Tokens.NONE);
                    if (number != null) {
                        number.setDisplay(false);
                        Tokens.name(number, Tokens.NONE);
                    }
                    kinds[index][slot] = KIND_NONE;
                    opens[index][slot] = null;
                    pings[index][slot] = null;
                    lists[index][slot] = null;
                    choices[index][slot] = null;
                    chosen[index][slot] = null;
                    forget(index, slot);
                    continue;
                }
                CompoundTag entry = list.getCompound(slot);
                Button press = row.presses.get(slot);
                Component label = Pages.readText(registries, entry.get(LABEL));
                ItemStack icon = ItemStack.parse(registries, entry.getCompound(ICON))
                    .orElse(ItemStack.EMPTY);
                row.acts.get(slot).style(style -> style.background(new ItemStackTexture(icon)));
                kinds[index][slot] = entry.getInt(KIND);
                opens[index][slot] = kinds[index][slot] == KIND_OPEN
                    ? ResourceLocation.tryParse(entry.getString(TARGET)) : null;
                pings[index][slot] = kinds[index][slot] == KIND_PING
                    ? NbtUtils.readBlockPos(entry, TARGET).orElse(null) : null;
                lists[index][slot] = kinds[index][slot] == KIND_EDIT && !entry.getString(TARGET).isEmpty()
                    ? entry.getString(TARGET) : null;
                boolean choosing = kinds[index][slot] == KIND_EDIT && entry.contains(CHOSEN);
                choices[index][slot] = choosing ? entry.getString(SETTING) : null;
                chosen[index][slot] = choosing ? ResourceLocation.tryParse(entry.getString(CHOSEN)) : null;

                boolean counts = number != null && kinds[index][slot] == KIND_EDIT
                    && entry.contains(RAW);
                press.setDisplay(!counts);
                press.style(style -> style.tooltips(label));

                Tokens.name(press, counts ? Tokens.NONE : token(kinds[index][slot], index, slot));
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
