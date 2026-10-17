package io.github.izakyl.folkways.plugins.person.name;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.person.PoolSets;
import io.github.izakyl.folkways.plugins.person.name.ColonyNames.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

// The colony's name pool: its given names or its surnames a page at a time, each with a button to strike it,
// a field to add one, and the sets a data pack offers to replace the pool whole.
public final class NamesPage implements Draw {

    // A page short enough that the window never runs past the bottom of the screen.
    private static final int ROW_SLOTS = 10;

    private static final String NAMES = "names";
    private static final String PART = "part";
    private static final String TOTAL = "total";

    private Part part = Part.GIVEN;
    private int offset;
    private String typed = "";
    private final List<String> window = new ArrayList<>();

    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Button> removers = new ArrayList<>();
    private final Label counter = Rows.name(Component.empty());

    @Override
    public UIElement build(Player player) {
        UIElement table = Rows.table();
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Button remove = new Button()
                .setText(Component.translatable("folkways.action.delete"))
                .setOnServerClick(event -> remove(player, index));
            remove.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            UIElement row = Rows.row().addChildren(name, remove);
            labels.add(name);
            removers.add(remove);
            slots.add(row);
            table.addChildren(row);
        }

        TextField field = new TextField();
        field.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        field.bind(DataBindingBuilder.stringC2S(text -> typed = text).build());
        Tokens.name(field, "folkways.pool.names.field");
        Button add = named("folkways.pool.names.add", new Button()
            .setText(Component.translatable("folkways.pool.add"))
            .setOnServerClick(event -> add(player)));
        add.layout(layout -> layout.flexShrink(0));

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(
            Rows.note(Component.translatable("folkways.pool.names.help")),
            new PoolSets("names", NamesPage::offered, NamesPage::replace).build(player),
            table));
        UIElement page = Rows.page().addChildren(
            Rows.strip().addChildren(
                tab("folkways.pool.names.given", Part.GIVEN),
                tab("folkways.pool.names.surname", Part.SURNAME),
                pager("<", -1),
                pager(">", 1),
                counter),
            Rows.strip().addChildren(field, add),
            scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(this::fill)
            .build()
            .getSyncValue());
        return page;
    }

    private Button tab(String key, Part shows) {
        Button press = named(key, new Button()
            .setText(Component.translatable(key))
            .setOnServerClick(event -> {
                part = shows;
                offset = 0;
            }));
        press.layout(layout -> layout.flexShrink(0));
        return press;
    }

    private Button pager(String glyph, int direction) {
        Button press = new Button().setText(Component.literal(glyph));
        press.layout(layout -> layout.flexShrink(0));
        press.setOnServerClick(event -> offset += direction * ROW_SLOTS);
        return press;
    }

    private static Button named(String token, Button press) {
        Tokens.name(press, token);
        return press;
    }

    private Optional<NamePool> pool(Player player) {
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ColonyNames.of(colony.get(), level.get().registryAccess()));
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        window.clear();
        List<String> listed = pool(player).map(found -> ColonyNames.listOf(found, part)).orElseGet(List::of);
        offset = clamp(offset, listed.size());
        for (int index = offset; index < Math.min(listed.size(), offset + ROW_SLOTS); index++) {
            rows.add(StringTag.valueOf(listed.get(index)));
            window.add(listed.get(index));
        }
        tag.put(NAMES, rows);
        tag.putString(PART, part.name());
        tag.putInt(TOTAL, listed.size());
        return tag;
    }

    private static int clamp(int offset, int total) {
        int last = Math.max(0, total - 1) / ROW_SLOTS * ROW_SLOTS;
        return Math.max(0, Math.min(offset, last));
    }

    private void add(Player player) {
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            ColonyNames.add(colony.get(), level.get().registryAccess(), part, typed);
        }
    }

    private void remove(Player player, int slot) {
        if (slot < 0 || slot >= window.size()) {
            return;
        }
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            ColonyNames.remove(colony.get(), level.get().registryAccess(), part, window.get(slot));
        }
    }

    private static List<PoolSets.Offered> offered(Player player) {
        return Pages.levelOf(player)
            .map(level -> ResidentNames.sets(level.registryAccess()).stream()
                .map(set -> new PoolSets.Offered(set.getKey(), "folkways.pool.names.holds",
                    set.getValue().names().size(), set.getValue().surnames().size()))
                .toList())
            .orElseGet(List::of);
    }

    private static void replace(Player player, ResourceLocation set) {
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            ResidentNames.set(level.get().registryAccess(), set)
                .ifPresent(chosen -> ColonyNames.replace(colony.get(), chosen));
        }
    }

    private void fill(Tag tag) {
        CompoundTag reading = tag instanceof CompoundTag compound ? compound : new CompoundTag();
        ListTag rows = reading.getList(NAMES, Tag.TAG_STRING);
        boolean given = !Part.SURNAME.name().equals(reading.getString(PART));
        counter.setText(Component.translatable(
            given ? "folkways.pool.names.given.count" : "folkways.pool.names.surname.count",
            reading.getInt(TOTAL)));
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            if (shown) {
                String name = rows.getString(slot);
                labels.get(slot).setText(Component.literal(name));
                Tokens.name(removers.get(slot), "folkways.pool.names.remove." + slot);
            } else {
                Tokens.name(removers.get(slot), Tokens.NONE);
            }
        }
    }
}
