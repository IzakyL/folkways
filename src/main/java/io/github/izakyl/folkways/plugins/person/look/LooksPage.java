package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import io.github.izakyl.folkways.core.api.colony.Books;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.plugins.person.PoolSets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

// The colony's look pool: every look there is, each in or out, and the sets that replace it whole.
public final class LooksPage implements Draw {

    // A page short enough that the window never runs past the bottom of the screen.
    private static final int ROW_SLOTS = 10;

    private static final String LOOKS = "looks";
    private static final String ID = "id";
    private static final String ALLOWED = "allowed";
    private static final String TOTAL = "total";
    private static final String ADMITTED = "admitted";

    private int offset;
    private final List<ResourceLocation> window = new ArrayList<>();

    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> names = new ArrayList<>();
    private final List<Button> switches = new ArrayList<>();
    private final Label counter = Rows.name(Component.empty());

    @Override
    public UIElement build(Player player) {
        UIElement table = Rows.table();
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            int index = slot;
            Label name = Rows.name(Component.empty());
            Button admit = new Button()
                .setText(Component.empty())
                .setOnServerClick(event -> toggle(player, index));
            admit.layout(layout -> layout.widthAuto().flexShrink(0).height(Rows.ROW_HEIGHT - 2));
            UIElement row = Rows.row().addChildren(name, admit);
            names.add(name);
            switches.add(admit);
            slots.add(row);
            table.addChildren(row);
        }

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(Rows.page().addChildren(
            Rows.note(Component.translatable("folkways.pool.looks.help")),
            new PoolSets("looks", LooksPage::offered, LooksPage::replace).build(player),
            table));
        UIElement page = Rows.page().addChildren(
            Rows.strip().addChildren(
                pager("<", event -> movePage(player, -1)),
                pager(">", event -> movePage(player, 1)),
                counter),
            scroller);
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(this::fill)
            .build()
            .getSyncValue());
        return page;
    }

    private static Button pager(String glyph, com.lowdragmc.lowdraglib2.gui.ui.event.UIEventListener on) {
        Button press = new Button().setText(Component.literal(glyph));
        press.layout(layout -> layout.flexShrink(0));
        press.setOnServerClick(on);
        return press;
    }

    private Tag reading(Player player) {
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        window.clear();
        int total = 0;
        int admitted = 0;
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            LookPool pool = LookPool.of(colony.get());
            List<ResourceKey<ResidentLook>> keys =
                ResidentLooks.keysInPool(level.get().registryAccess());
            total = keys.size();
            admitted = (int) keys.stream().filter(key -> pool.allows(key.location())).count();
            offset = clamp(offset, total);
            for (int index = offset; index < Math.min(total, offset + ROW_SLOTS); index++) {
                ResourceLocation look = keys.get(index).location();
                CompoundTag row = new CompoundTag();
                row.putString(ID, look.toString());
                row.putBoolean(ALLOWED, pool.allows(look));
                rows.add(row);
                window.add(look);
            }
        }
        tag.put(LOOKS, rows);
        tag.putInt(TOTAL, total);
        tag.putInt(ADMITTED, admitted);
        return tag;
    }

    private static int clamp(int offset, int total) {
        int last = Math.max(0, total - 1) / ROW_SLOTS * ROW_SLOTS;
        return Math.max(0, Math.min(offset, last));
    }

    private void movePage(Player player, int direction) {
        int total = Pages.levelOf(player)
            .map(level -> ResidentLooks.keysInPool(level.registryAccess()).size())
            .orElse(0);
        offset = clamp(offset + direction * ROW_SLOTS, total);
    }

    private void toggle(Player player, int slot) {
        if (slot < 0 || slot >= window.size()) {
            return;
        }
        ResourceLocation look = window.get(slot);
        Books.acting(player).ifPresent(colony ->
            LookPool.set(colony, look, !LookPool.of(colony).allows(look)));
    }

    private static List<PoolSets.Offered> offered(Player player) {
        return Pages.levelOf(player)
            .map(level -> LookSet.all(level.registryAccess()).stream()
                .map(set -> new PoolSets.Offered(set.getKey(), "folkways.pool.looks.holds",
                    set.getValue().looks().size()))
                .toList())
            .orElseGet(List::of);
    }

    private static void replace(Player player, ResourceLocation set) {
        Optional<Colony> colony = Books.acting(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isPresent() && level.isPresent()) {
            LookSet.of(level.get().registryAccess(), set)
                .ifPresent(chosen -> LookPool.replace(colony.get(), chosen.looks()));
        }
    }

    private void fill(Tag tag) {
        CompoundTag reading = tag instanceof CompoundTag compound ? compound : new CompoundTag();
        ListTag rows = reading.getList(LOOKS, Tag.TAG_COMPOUND);
        int total = reading.getInt(TOTAL);
        counter.setText(total == 0
            ? Component.translatable("folkways.settings.looks.empty")
            : Component.translatable("folkways.settings.looks.count", reading.getInt(ADMITTED), total));
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            slots.get(slot).setDisplay(shown);
            if (shown) {
                CompoundTag row = rows.getCompound(slot);
                ResourceLocation look = ResourceLocation.parse(row.getString(ID));
                names.get(slot).setText(Component.literal(look.toString()));
                switches.get(slot).setText(Component.translatable(
                    row.getBoolean(ALLOWED) ? "folkways.action.on" : "folkways.action.off"));
                Tokens.name(switches.get(slot), "folkways.pool.looks.toggle." + Tokens.of(look));
            } else {
                Tokens.name(switches.get(slot), Tokens.NONE);
            }
        }
    }
}
