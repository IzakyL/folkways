package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Switch;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.Zone;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

final class ZoneSettings {

    private static final int SLOTS = 24;

    private static final String OPEN = "open";
    private static final String TITLE = "title";
    private static final String SETTINGS = "settings";
    private static final String KEY = "key";
    private static final String NAME = "name";
    private static final String ICON = "icon";
    private static final String VALUE = "value";
    private static final String KIND = "kind";
    private static final String RAW = "raw";
    private static final String MIN = "min";
    private static final String MAX = "max";
    private static final String OPTIONS = "options";

    private static final int KIND_NONE = 0;
    private static final int KIND_ITEMS = 1;
    private static final int KIND_COUNT = 2;
    private static final int KIND_CHOICE = 3;
    private static final int KIND_FLAG = 4;

    private UUID held;
    private final List<String> sent = new ArrayList<>();

    private final List<Row> rows = new ArrayList<>();
    private final Label title = new Label();
    private final Label noSettings = Rows.note(Component.translatable("folkways.zoneconfig.no_settings"));

    private final String[] drawn = new String[SLOTS];
    private final Controls.Echo echoed = new Controls.Echo(SLOTS);

    private ZoneSettings() {
    }

    static ZoneSettings create() {
        return new ZoneSettings();
    }

    void hold(UUID zone) {
        held = zone;
    }

    UIElement build(Player player) {
        UIElement table = Rows.table();
        ChoicePicker.Asking asking = new ChoicePicker.Asking(table, (key, option) -> choose(player, key, option));
        for (int slot = 0; slot < SLOTS; slot++) {
            int index = slot;
            Row row = new Row();
            row.icon = Rows.icon(ItemStack.EMPTY);
            row.name = Rows.name(Component.empty());
            row.value = Rows.value(Component.empty());

            row.press = new Button().noText();
            row.press.layout(layout -> layout.widthAuto()
                .minWidth(SettingsPage.CONTROL_MIN).flexShrink(0));
            row.press.addChildren(row.value);

            row.press.setOnClick(event -> pick(index, asking));

            row.number = Controls.count(0, Integer.MAX_VALUE);
            stamp(row.number, index, row.number::getValue,
                (key, text) -> write(player, index, key, text));

            row.flag = Controls.flag();
            row.flag.addServerEventListener(UIEvents.CLICK, event -> toggle(player, index));

            row.element = Rows.row().addChildren(row.icon, row.name,
                row.press, row.number, row.flag);
            rows.add(row);
            table.addChildren(row.element);
        }
        title.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        title.textStyle(style -> style.textWrap(
            com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap.HOVER_ROLL));

        Button back = new Button()
            .setText(Component.translatable("folkways.action.back"))
            .setOnServerClick(event -> held = null);
        Tokens.name(back, "folkways.selection.back");
        back.layout(layout -> layout.flexShrink(0));
        Button release = new Button()
            .setText(Component.translatable("folkways.selection.release"))
            .setOnServerClick(event -> release(player));
        release.addClass("folkways_danger");
        release.style(style -> style.tooltips(Component.translatable("folkways.action.delete")));
        Tokens.name(release, "folkways.selection.release");
        release.layout(layout -> layout.flexShrink(0));
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(table);

        UIElement page = Rows.page().addChildren(Rows.strip().addChildren(back, title, release), noSettings, scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));
        return page;
    }

    private void release(Player player) {
        UUID zone = held;
        held = null;
        if (zone == null) {
            return;
        }
        Pages.colonyOf(player).ifPresent(colony -> {
            var front = io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(colony);
            front.erase(zone);
        });
    }

    private void stamp(UIElement control, int slot, Supplier<String> value,
            BiConsumer<String, String> apply) {
        SimpleBinding<String> binding = DataBindingBuilder.stringC2S(sealed -> apply.accept(
            Controls.stampedKey(sealed), Controls.stampedValue(sealed))).build();
        binding.setRemoteDataSource(IDataSource.of(ignored -> {
        }, () -> Controls.stamped(Objects.requireNonNullElse(drawn[slot], ""), value.get())));
        control.addSyncValue(binding.getSyncValue());
    }

    Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        sent.clear();
        Optional<Selected> zone = zone(player);
        tag.putBoolean(OPEN, zone.isPresent());
        if (zone.isEmpty()) {
            return tag;
        }
        HolderLookup.Provider registries = player.registryAccess();
        tag.put(TITLE, Pages.text(registries,
            line(zone.get().delegation(), zone.get().position())));
        ListTag list = new ListTag();
        for (Schema.Setting setting : SettingsPage.schemaOf(zone.get().delegation()).settings()) {
            if (sent.size() >= SLOTS) {
                break;
            }
            sent.add(setting.key());
            CompoundTag row = new CompoundTag();
            row.putString(KEY, setting.key());
            row.putString(NAME, setting.nameKey());
            row.putString(ICON, setting.icon().toString());
            row.put(VALUE, Pages.text(registries, SettingsPage.label(player, on(zone.get()), setting)));
            row.putInt(KIND, kindOf(setting));
            describe(player, zone.get(), setting, row);
            list.add(row);
        }
        tag.put(SETTINGS, list);
        return tag;
    }

    private void describe(Player player, Selected zone, Schema.Setting setting, CompoundTag row) {
        SettingsPage.Scope where = on(zone);
        switch (setting) {
            case Schema.Setting.Count count -> {
                row.putInt(MIN, count.min());
                row.putInt(MAX, count.max());
                row.putString(RAW, Integer.toString(SettingsPage.countOf(player, where, count)));
            }
            case Schema.Setting.Choice choice -> {
                row.putString(RAW, SettingsPage.choiceOf(player, where, choice).toString());
                ListTag options = new ListTag();
                choice.options().forEach(option -> options.add(StringTag.valueOf(option.toString())));
                row.put(OPTIONS, options);
            }
            case Schema.Setting.Flag flag ->
                row.putString(RAW, SettingsPage.flagOf(player, where, flag) ? "1" : "0");
            case Schema.Setting.Items ignored -> row.putString(RAW, "");
        }
    }

    private static int kindOf(Schema.Setting setting) {
        return switch (setting) {
            case Schema.Setting.Items ignored -> KIND_ITEMS;
            case Schema.Setting.Count ignored -> KIND_COUNT;
            case Schema.Setting.Choice ignored -> KIND_CHOICE;
            case Schema.Setting.Flag ignored -> KIND_FLAG;
        };
    }

    private void toggle(Player player, int slot) {
        setting(player, slot).ifPresent(pair -> {
            if (pair.setting() instanceof Schema.Setting.Flag flag) {
                SettingsPage.Scope where = on(pair.zone());
                SettingsPage.setFlag(player, where, flag, !SettingsPage.flagOf(player, where, flag));
            }
        });
    }

    private void choose(Player player, String key, ResourceLocation option) {
        setting(player, sent.indexOf(key)).ifPresent(pair -> {
            if (pair.setting() instanceof Schema.Setting.Choice choice) {
                SettingsPage.setChoice(player, on(pair.zone()), choice, option);
            }
        });
    }

    private void pick(int slot, ChoicePicker.Asking asking) {
        Row row = rows.get(slot);
        if (row.kind != KIND_CHOICE || drawn[slot] == null) {
            return;
        }
        String key = drawn[slot];
        ChoicePicker.open(row.press, row.nameKey, row.options, ResourceLocation.tryParse(row.raw),
            option -> asking.ask(key, option));
    }

    private void write(Player player, int slot, String key, String value) {
        if (slot >= sent.size() || !sent.get(slot).equals(key)) {
            return;
        }
        setting(player, slot).ifPresent(pair -> {
            if (pair.setting() instanceof Schema.Setting.Count count) {
                SettingsPage.setCount(player, on(pair.zone()), count, value);
            }
        });
    }

    private record Declared(Selected zone, Schema.Setting setting) {
    }

    private Optional<Declared> setting(Player player, int slot) {
        if (slot < 0 || slot >= sent.size()) {
            return Optional.empty();
        }
        String key = sent.get(slot);
        return zone(player).flatMap(zone -> SettingsPage.schemaOf(zone.delegation()).find(key)
            .map(setting -> new Declared(zone, setting)));
    }

    private record Selected(UUID id, ResourceLocation delegation, net.minecraft.core.BlockPos position,
                            SettingsPage.Scope scope) { }

    private Optional<Selected> zone(Player player) {
        if (held == null) return Optional.empty();
        return Pages.colonyOf(player).flatMap(colony -> {
            var front = io.github.izakyl.folkways.front.engine.colony.ColonyFront.of(colony);
            return front.zone(held).map(zone -> new Selected(zone.id(), zone.delegation(),
                Zone.over(zone).min(), new SettingsPage.Scope.OnZone(zone.id(), zone.delegation())))
                .or(() -> front.path(held).map(path -> new Selected(path.id(), path.delegation(),
                    path.points().getFirst(), new SettingsPage.Scope.OnPath(path.id(), path.delegation()))));
        });
    }

    private static SettingsPage.Scope on(Selected zone) { return zone.scope(); }

    private static Component line(ResourceLocation delegation, net.minecraft.core.BlockPos min) {
        return Component.translatable(
                "folkways.delegation." + delegation.getNamespace() + "." + delegation.getPath())
            .append(" ")
            .append(Component.literal(min.getX() + "," + min.getY() + "," + min.getZ()));
    }

    boolean show(Player player, Tag tag) {
        CompoundTag reading = tag instanceof CompoundTag compound ? compound : new CompoundTag();
        boolean open = reading.getBoolean(OPEN);
        HolderLookup.Provider registries = player.registryAccess();
        title.setText(Pages.readText(registries, reading.get(TITLE)));
        ListTag list = reading.getList(SETTINGS, Tag.TAG_COMPOUND);
        noSettings.setDisplay(open && list.isEmpty());
        for (int slot = 0; slot < rows.size(); slot++) {
            Row row = rows.get(slot);
            boolean shown = slot < list.size();
            row.element.setDisplay(shown);
            if (!shown) {
                drawn[slot] = null;
                echoed.forget(slot);
                row.kind = KIND_NONE;
                wear(row, KIND_NONE);
                name(row, Tokens.NONE, KIND_NONE);
                continue;
            }
            CompoundTag entry = list.getCompound(slot);
            row.name.setText(Component.translatable(entry.getString(NAME)));
            row.nameKey = entry.getString(NAME);
            row.kind = entry.getInt(KIND);
            row.raw = entry.getString(RAW);
            row.options = entry.getList(OPTIONS, Tag.TAG_STRING).stream()
                .map(option -> ResourceLocation.tryParse(option.getAsString()))
                .filter(Objects::nonNull)
                .toList();
            row.value.setText(Pages.readText(registries, entry.get(VALUE)));
            ItemStack icon = SettingsPage.iconOf(ResourceLocation.parse(entry.getString(ICON)));
            row.icon.style(style -> style.background(new ItemStackTexture(icon)));
            fit(row, slot, entry);
            name(row, entry.getString(KEY), entry.getInt(KIND));
        }
        return open;
    }

    private void fit(Row row, int slot, CompoundTag entry) {
        String key = entry.getString(KEY);
        int kind = entry.getInt(KIND);
        boolean rekeyed = !key.equals(drawn[slot]);
        if (rekeyed) {
            drawn[slot] = key;
            echoed.forget(slot);
        }
        wear(row, kind);
        String raw = entry.getString(RAW);
        switch (kind) {
            case KIND_COUNT -> {
                if (rekeyed) {
                    Controls.ranged(row.number, entry.getInt(MIN), entry.getInt(MAX));
                }
                if (echoed.fresh(slot, raw)) {
                    row.number.setValue(raw, false);
                }
            }
            case KIND_FLAG -> {
                if (echoed.fresh(slot, raw)) {
                    row.flag.setOn("1".equals(raw), false);
                }
            }
            default -> {
            }
        }
    }

    private static void wear(Row row, int kind) {
        row.press.setDisplay(kind == KIND_ITEMS || kind == KIND_CHOICE);
        row.number.setDisplay(kind == KIND_COUNT);
        row.flag.setDisplay(kind == KIND_FLAG);
    }

    private static void name(Row row, String key, int kind) {
        boolean blank = key.isEmpty();
        Tokens.name(row.element, blank ? Tokens.NONE : "folkways.zoneconfig.setting." + key);
        String token = blank ? Tokens.NONE : "folkways.zoneconfig.value." + key;
        Tokens.name(row.press, kind == KIND_ITEMS || kind == KIND_CHOICE ? token : Tokens.NONE);
        Tokens.name(row.number, kind == KIND_COUNT ? token : Tokens.NONE);
        Tokens.name(row.flag, kind == KIND_FLAG ? token : Tokens.NONE);
    }

    private static final class Row {
        private UIElement element;
        private UIElement icon;
        private Label name;
        private Label value;
        private Button press;
        private TextField number;
        private Switch flag;
        private int kind;
        private String nameKey = "";
        private String raw = "";
        private List<ResourceLocation> options = List.of();
    }
}
