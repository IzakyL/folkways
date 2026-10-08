package io.github.izakyl.folkways.plugins.person;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import dev.vfyjxf.taffy.style.FlexWrap;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

// The sets a data pack offers for a pool, a button each, any of which replaces the colony's pool whole.
public final class PoolSets {

    private static final int SET_SLOTS = 12;

    private static final String SETS = "sets";
    private static final String ID = "id";
    private static final String DETAIL = "detail";
    private static final String COUNTS = "counts";

    // detailKey is said with the counts, e.g. how many names and surnames the set holds.
    public record Offered(ResourceLocation id, String detailKey, int... counts) {
    }

    private final String kind;
    private final Function<Player, List<Offered>> offered;
    private final BiConsumer<Player, ResourceLocation> replace;
    private final List<ResourceLocation> window = new ArrayList<>();

    // kind names the pool, for the set's display name and the buttons' tokens: "names" or "looks".
    public PoolSets(String kind, Function<Player, List<Offered>> offered,
            BiConsumer<Player, ResourceLocation> replace) {
        this.kind = kind;
        this.offered = offered;
        this.replace = replace;
    }

    public UIElement build(Player player) {
        UIElement shelf = Rows.strip();
        shelf.layout(layout -> layout.flexWrap(FlexWrap.WRAP).flexShrink(0).gapAll(Rows.GAP));
        Label caption = Rows.value(Component.translatable("folkways.pool.sets"));
        caption.layout(layout -> layout.flexShrink(0));
        caption.textStyle(style -> style.textColor(Rows.QUIET));
        shelf.addChildren(caption);
        List<Button> presses = new ArrayList<>();
        for (int slot = 0; slot < SET_SLOTS; slot++) {
            int index = slot;
            Button press = new Button()
                .setText(Component.empty())
                .setOnServerClick(event -> replaceAt(player, index));
            press.layout(layout -> layout.widthAuto().flexShrink(0));
            press.setDisplay(false);
            presses.add(press);
            shelf.addChildren(press);
        }
        shelf.addSyncValue(DataBindingBuilder.tagS2C(() -> reading(player))
            .onSyncReceived(tag -> {
                ListTag rows = tag instanceof CompoundTag compound
                    ? compound.getList(SETS, Tag.TAG_COMPOUND) : new ListTag();
                shelf.setDisplay(!rows.isEmpty());
                for (int slot = 0; slot < SET_SLOTS; slot++) {
                    Button press = presses.get(slot);
                    boolean shown = slot < rows.size();
                    press.setDisplay(shown);
                    if (!shown) {
                        Tokens.name(press, Tokens.NONE);
                        continue;
                    }
                    CompoundTag row = rows.getCompound(slot);
                    ResourceLocation id = ResourceLocation.parse(row.getString(ID));
                    Object[] counts = Arrays.stream(row.getIntArray(COUNTS)).boxed().toArray();
                    press.setText(nameOf(id));
                    press.style(style -> style.tooltips(
                        Component.translatable("folkways.pool.replace"),
                        Component.translatable(row.getString(DETAIL), counts)));
                    Tokens.name(press, "folkways.pool." + kind + ".replace." + Tokens.of(id));
                }
            })
            .build()
            .getSyncValue());
        return shelf;
    }

    private Component nameOf(ResourceLocation id) {
        return Component.translatableWithFallback(
            "folkways.pool." + kind + "." + id.getNamespace() + "." + id.getPath(), id.toString());
    }

    private Tag reading(Player player) {
        window.clear();
        ListTag rows = new ListTag();
        for (Offered set : offered.apply(player)) {
            if (window.size() >= SET_SLOTS) {
                break;
            }
            window.add(set.id());
            CompoundTag row = new CompoundTag();
            row.putString(ID, set.id().toString());
            row.putString(DETAIL, set.detailKey());
            row.putIntArray(COUNTS, set.counts());
            rows.add(row);
        }
        CompoundTag tag = new CompoundTag();
        tag.put(SETS, rows);
        return tag;
    }

    private void replaceAt(Player player, int slot) {
        if (slot >= 0 && slot < window.size()) {
            replace.accept(player, window.get(slot));
        }
    }
}
