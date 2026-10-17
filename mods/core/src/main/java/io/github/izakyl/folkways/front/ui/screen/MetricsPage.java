package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import dev.vfyjxf.taffy.style.FlexWrap;
import dev.vfyjxf.taffy.style.TaffyPosition;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.front.api.ui.Draw;
import io.github.izakyl.folkways.front.api.ui.Pages;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Signs;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import io.github.izakyl.folkways.front.engine.colony.ColonyMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

// What the colony holds in its storage, item by item: a grid of icons, each with its count tucked under it as in a
// chest, and its name and full count on hover.
public final class MetricsPage implements Draw {

    private static final int ROW_SLOTS = 60;
    private static final int SLOT = Rows.ICON_SIZE + 2;
    private static final int SLOT_GAP = 2;
    private static final int COUNT_HEIGHT = 9;
    private static final String SLOT_PREFIX = "folkways.metrics.stock.";

    private static final String STOCK = "stock";
    private static final String ITEM = "item";
    private static final String COUNT = "count";

    private ColonyMetrics reading;
    private int offset;
    private Button refresh;

    private final List<UIElement> slots = new ArrayList<>();
    private final List<Label> counts = new ArrayList<>();
    private final List<ItemStackTexture> icons = new ArrayList<>();

    private MetricsPage() {
    }

    public static MetricsPage create() {
        return new MetricsPage();
    }

    @Override
    public UIElement build(Player player) {
        UIElement table = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW).flexWrap(FlexWrap.WRAP)
                .widthPercent(100).gapAll(SLOT_GAP));
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            Label count = new Label();
            count.layout(layout -> layout.positionType(TaffyPosition.ABSOLUTE).right(0).bottom(0)
                .width(SLOT).height(COUNT_HEIGHT));
            count.textStyle(style -> style.adaptiveHeight(false).textWrap(TextWrap.NONE)
                .textAlignHorizontal(Horizontal.RIGHT).textAlignVertical(Vertical.BOTTOM)
                .textColor(Signs.COUNT_COLOR).textShadow(true));
            ItemStackTexture icon = new ItemStackTexture(ItemStack.EMPTY);
            UIElement cell = new UIElement()
                .layout(layout -> layout.width(SLOT).height(SLOT).flexShrink(0).paddingAll(1))
                .addChildren(new UIElement()
                    .layout(layout -> layout.width(Rows.ICON_SIZE).height(Rows.ICON_SIZE))
                    .style(style -> style.background(icon)), count);
            icons.add(icon);
            counts.add(count);
            slots.add(cell);
            table.addChildren(cell);
        }

        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(table);
        refresh = named("folkways.metrics.refresh", new Button()
            .setText(Component.translatable("folkways.metrics.refresh"))
            .setOnServerClick(event -> reading = null));
        UIElement page = Rows.page().addChildren(
            scroller,
            Rows.strip().addChildren(
                new Button()
                    .setText(Component.literal("<"))
                    .setOnServerClick(event -> movePage(-1)),
                new Button()
                    .setText(Component.literal(">"))
                    .setOnServerClick(event -> movePage(1))));
        page.layout(layout -> layout.flexGrow(1).flexShrink(1).minHeight(0));

        page.addSyncValue(DataBindingBuilder.tagS2C(() -> stockTag(player))
            .onSyncReceived(this::fill)
            .build()
            .getSyncValue());
        return page;
    }

    @Override
    public List<UIElement> actions() {
        return refresh == null ? List.of() : List.of(refresh);
    }

    private static Button named(String token, Button press) {
        Tokens.name(press, token);
        return press;
    }

    private ColonyMetrics reading(Player player) {
        if (reading != null) {
            return reading;
        }
        Optional<Colony> colony = Pages.colonyOf(player);
        Optional<ServerLevel> level = Pages.levelOf(player);
        if (colony.isEmpty() || level.isEmpty()) {
            return ColonyMetrics.EMPTY;
        }
        reading = ColonyMetrics.of(level.get().getServer(), colony.get());
        return reading;
    }

    private Tag stockTag(Player player) {
        ColonyMetrics held = reading(player);
        offset = clamp(offset, held.stock().size());
        CompoundTag tag = new CompoundTag();
        ListTag rows = new ListTag();
        for (int index = offset; index < Math.min(held.stock().size(), offset + ROW_SLOTS); index++) {
            ColonyMetrics.Held line = held.stock().get(index);
            CompoundTag row = new CompoundTag();
            row.putString(ITEM, line.item().toString());
            row.putInt(COUNT, line.count());
            rows.add(row);
        }
        tag.put(STOCK, rows);
        return tag;
    }

    private static int clamp(int offset, int total) {
        int last = Math.max(0, total - 1) / ROW_SLOTS * ROW_SLOTS;
        return Math.max(0, Math.min(offset, last));
    }

    private void movePage(int direction) {
        int total = reading == null ? 0 : reading.stock().size();
        offset = clamp(offset + direction * ROW_SLOTS, total);
    }

    private void fill(Tag tag) {
        ListTag rows = tag instanceof CompoundTag compound
            ? compound.getList(STOCK, Tag.TAG_COMPOUND)
            : new ListTag();
        for (int slot = 0; slot < ROW_SLOTS; slot++) {
            boolean shown = slot < rows.size();
            UIElement cell = slots.get(slot);
            cell.setDisplay(shown);
            if (!shown) {
                Tokens.name(cell, Tokens.NONE);
                continue;
            }
            CompoundTag row = rows.getCompound(slot);
            ResourceLocation item = ResourceLocation.parse(row.getString(ITEM));
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.getOptional(item).orElse(Items.BARRIER));
            int count = row.getInt(COUNT);
            Tokens.name(cell, SLOT_PREFIX + Tokens.of(item));
            icons.get(slot).setItems(stack);
            counts.get(slot).setText(Component.literal(count > 1 ? Signs.compact(count) : ""));
            cell.style(style -> style.tooltips(stack.getHoverName(),
                Component.literal(Integer.toString(count)).withStyle(ChatFormatting.GRAY)));
        }
    }
}
