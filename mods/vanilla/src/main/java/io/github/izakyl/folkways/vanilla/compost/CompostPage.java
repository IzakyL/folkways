package io.github.izakyl.folkways.vanilla.compost;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.front.api.ui.Picker;
import io.github.izakyl.folkways.core.front.api.ui.Draw;
import io.github.izakyl.folkways.core.front.api.ui.Pages;
import io.github.izakyl.folkways.core.front.api.ui.Rows;
import io.github.izakyl.folkways.core.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

/** Server-authorized controls for composting and its dynamically discovered seed reserves. */
public final class CompostPage implements Draw {
    private static final int ROW_SLOTS = 10;
    private int offset;
    private List<Item> window = List.of();
    private final List<UIElement> slots = new ArrayList<>();
    private final List<UIElement> icons = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final String[] drawn = new String[ROW_SLOTS];
    private final List<ReserveInput> numbers = new ArrayList<>();
    private ReserveInput defaultCount;
    private final List<Label> sources = new ArrayList<>();
    private final Label counter = Rows.value(Component.empty());
    private final Label empty = Rows.note(Component.translatable("folkways.settings.compost.empty"));

    @Override
    public UIElement build(Player player) {
        Toggle enabled = new Toggle().noText();
        enabled.layout(layout -> layout.flexShrink(0));
        enabled.bind(DataBindingBuilder.bool(() -> colony(player).map(CompostSettings::enabled).orElse(false),
            on -> colony(player).ifPresent(colony -> CompostSettings.enabled(colony, on))).build());
        Tokens.name(enabled, "folkways.settings.compost.enabled");
        defaultCount = new ReserveInput(() -> "default", (key, count) ->
            colony(player).ifPresent(colony -> CompostSettings.defaultReserve(colony, count)));
        Tokens.name(defaultCount.field, "folkways.settings.compost.default_reserve");
        UIElement table = Rows.table();
        Picker.Asking resets = new Picker.Asking(table, (key, ignored) ->
            colony(player).ifPresent(colony -> window.stream()
                .filter(item -> BuiltInRegistries.ITEM.getKey(item).toString().equals(key)).findFirst()
                .ifPresent(item -> CompostSettings.reset(colony, item))));
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            UIElement icon = Rows.icon(ItemStack.EMPTY);
            Label name = Rows.name(Component.empty());
            Label source = Rows.value(Component.empty());
            ReserveInput count = new ReserveInput(() -> drawn[index], (key, value) ->
                item(index).filter(item -> BuiltInRegistries.ITEM.getKey(item).toString().equals(key))
                    .ifPresent(item -> colony(player).ifPresent(colony -> {
                        if (value != CompostSettings.reserve(colony, item)) {
                            CompostSettings.reserve(colony, item, value);
                        }
                    })));
            Tokens.name(count.field, "folkways.settings.compost.reserve." + slot);
            Button reset = new Button().setText(Component.translatable("folkways.settings.compost.use_default"))
                .setOnClick(event -> {
                    if (drawn[index] != null) resets.ask(drawn[index], ResourceLocation.withDefaultNamespace("air"));
                });
            reset.layout(layout -> layout.flexShrink(0));
            Tokens.name(reset, "folkways.settings.compost.reset." + slot);
            UIElement row = Rows.row().addChildren(icon, name, source, count.field, reset);
            slots.add(row);
            icons.add(icon);
            names.add(name);
            numbers.add(count);
            sources.add(source);
            table.addChildren(row);
        }
        var scroll = Rows.fills(Rows.box());
        scroll.addScrollViewChild(Rows.page().addChildren(table, empty));
        UIElement page = Rows.page().addChildren(
            Rows.row().addChildren(Rows.icon(Items.COMPOSTER.getDefaultInstance()),
                Rows.name(Component.translatable("folkways.settings.compost.enabled")), enabled),
            Rows.row().addChildren(Rows.icon(Items.WHEAT_SEEDS.getDefaultInstance()),
                Rows.name(Component.translatable("folkways.settings.compost.default_reserve")), defaultCount.field),
            Rows.strip().addChildren(Rows.name(Component.translatable("folkways.settings.compost.seeds")),
                pager("<", -1), pager(">", 1), counter), scroll);
        Rows.fills(page);
        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player)).onSyncReceived(this::fill).build().getSyncValue());
        return page;
    }

    private static Optional<Colony> colony(Player player) { return Pages.colonyOf(player); }
    private Optional<Item> item(int slot) { return slot >= 0 && slot < window.size() ? Optional.of(window.get(slot)) : Optional.empty(); }

    private Button pager(String label, int direction) {
        Button button = new Button().setText(Component.literal(label)).setOnServerClick(event -> offset += direction * ROW_SLOTS);
        button.layout(layout -> layout.flexShrink(0));
        Tokens.name(button, "folkways.settings.compost." + (direction < 0 ? "previous" : "next"));
        return button;
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        boolean pending = colony(player).map(colony -> {
            if (!colony.inventory().snapshot().coverage().complete()) {
                colony.inventory().ensureObserved();
                return true;
            }
            return false;
        }).orElse(false);
        List<Item> all = pending ? List.of() : colony(player).flatMap(colony -> Pages.levelOf(player)
            .map(level -> CompostSettings.discovered(colony, level.getServer()))).orElseGet(List::of);
        tag.putBoolean("pending", pending);
        offset = Math.clamp(offset, 0, Math.max(0, all.size() - 1) / ROW_SLOTS * ROW_SLOTS);
        window = all.subList(offset, Math.min(all.size(), offset + ROW_SLOTS));
        ListTag rows = new ListTag();
        for (Item item : window) {
            CompoundTag row = new CompoundTag();
            row.putString("item", BuiltInRegistries.ITEM.getKey(item).toString());
            ListTag alternatives = new ListTag();
            SeedReserves.alternatives(item).forEach(alternative ->
                alternatives.add(StringTag.valueOf(BuiltInRegistries.ITEM.getKey(alternative).toString())));
            row.put("alternatives", alternatives);
            row.putInt("count", colony(player).map(colony -> CompostSettings.reserve(colony, item)).orElse(CompostSettings.DEFAULT_RESERVE));
            row.putBoolean("override", colony(player).map(colony -> CompostSettings.overridden(colony, item)).orElse(false));
            rows.add(row);
        }
        tag.put("rows", rows);
        tag.putInt("default", colony(player).map(CompostSettings::defaultReserve)
            .orElse(CompostSettings.DEFAULT_RESERVE));
        tag.putInt("total", all.size());
        tag.putInt("page", offset / ROW_SLOTS + 1);
        tag.putInt("pages", Math.max(1, (all.size() + ROW_SLOTS - 1) / ROW_SLOTS));
        return tag;
    }

    private void fill(Tag value) {
        CompoundTag tag = value instanceof CompoundTag compound ? compound : new CompoundTag();
        defaultCount.receive(Integer.toString(tag.getInt("default")));
        ListTag rows = tag.getList("rows", Tag.TAG_COMPOUND);
        counter.setText(Component.translatable("folkways.settings.compost.page", tag.getInt("page"), tag.getInt("pages")));
        empty.setDisplay(tag.getInt("total") == 0);
        empty.setText(Component.translatable(tag.getBoolean("pending") ? "folkways.settlers.checking_stock"
            : "folkways.settings.compost.empty"));
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            if (!shown) {
                drawn[slot] = null;
                continue;
            }
            CompoundTag row = rows.getCompound(slot);
            String key = row.getString("item");
            String count = Integer.toString(row.getInt("count"));
            numbers.get(slot).receive(count);
            drawn[slot] = key;
            ResourceLocation id = ResourceLocation.tryParse(row.getString("item"));
            Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
            var name = Component.empty();
            ListTag alternatives = row.getList("alternatives", Tag.TAG_STRING);
            for (int index = 0; index < alternatives.size(); index++) {
                ResourceLocation alternative = ResourceLocation.tryParse(alternatives.getString(index));
                if (alternative == null) continue;
                if (!name.getSiblings().isEmpty()) name.append(" / ");
                name.append(BuiltInRegistries.ITEM.get(alternative).getDescription());
            }
            if (name.getSiblings().isEmpty()) name.append(item.getDescription());
            names.get(slot).setText(name);
            icons.get(slot).style(style -> style.background(new ItemStackTexture(item.getDefaultInstance())));
            sources.get(slot).setText(Component.translatable(row.getBoolean("override")
                ? "folkways.settings.compost.custom" : "folkways.settings.compost.inherited"));
        }
    }

    private static TextField number() {
        TextField field = new TextField();
        field.setNumbersOnlyInt(0, CompostSettings.MAX_RESERVE);
        field.layout(layout -> layout.width(48).flexShrink(0));
        return field;
    }

    private static final class ReserveInput {
        private final TextField field = number();
        private String echoed = "";
        private String editing;
        private String sent = "";
        private int sequence;

        private ReserveInput(Supplier<String> key, BiConsumer<String, Integer> received) {
            SimpleBinding<String> binding = DataBindingBuilder.stringC2S(sealed -> {
                String[] parts = sealed.split("\\|", 3);
                if (parts.length == 3 && !parts[1].isEmpty()) {
                    parse(parts[2]).ifPresent(count -> received.accept(parts[1], count));
                }
            }).build();
            binding.setRemoteDataSource(IDataSource.of(ignored -> { }, () -> sent));
            field.addSyncValue(binding.getSyncValue());
            field.addEventListener(UIEvents.FOCUS, event -> editing = key.get());
            field.addEventListener(UIEvents.BLUR, event -> {
                // Read the text, since LDLib2 keeps the last valid value when the editor is empty.
                parse(field.getRawText()).ifPresent(count -> {
                    if (editing != null && editing.equals(key.get()) && !Integer.toString(count).equals(echoed)) {
                        sent = ++sequence + "|" + editing + "|" + count;
                    }
                });
                field.setValue(echoed, false);
            });
            field.addEventListener(UIEvents.KEY_DOWN, event -> {
                if (event.keyCode == GLFW.GLFW_KEY_ENTER || event.keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    field.blur();
                    event.stopPropagation();
                }
            });
        }

        private void receive(String value) {
            echoed = value;
            if (!field.isFocused()) {
                field.setValue(value, false);
            }
        }
    }

    private static Optional<Integer> parse(String text) {
        try {
            int value = Integer.parseInt(text.trim());
            return value >= 0 && value <= CompostSettings.MAX_RESERVE ? Optional.of(value) : Optional.empty();
        } catch (NumberFormatException ignored) { return Optional.empty(); }
    }
}
