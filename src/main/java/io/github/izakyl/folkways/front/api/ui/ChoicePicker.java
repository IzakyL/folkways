package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.jetbrains.annotations.Nullable;

public final class ChoicePicker {

    private static final String CHOSEN = "folkways_chosen";

    private static final float WIDTH = 180f;
    private static final float ROW = 20f;
    private static final float ROW_GAP = 2f;
    private static final float CHROME = 76f;
    private static final int MOST_ROWS = 8;
    private static final float HOST_GAP = 4f;

    // How high a dialog must sit to show over every window the colony panel has open.
    private static IntSupplier aboveWindows = () -> 0;

    private ChoicePicker() {
    }

    public static void install(IntSupplier depth) {
        aboveWindows = depth;
    }

    public static Dialog open(UIElement host, Schema.Setting.Choice choice, ResourceLocation current,
            Consumer<ResourceLocation> confirmed) {
        return open(host, choice.nameKey(), choice.options(), current, confirmed);
    }

    public static Dialog open(UIElement host, String titleKey, List<ResourceLocation> options,
            @Nullable ResourceLocation current, Consumer<ResourceLocation> confirmed) {
        Dialog dialog = new Dialog();
        ResourceLocation[] picked = { options.contains(current) ? current : null };
        List<Button> rows = new ArrayList<>();
        UIElement list = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(ROW_GAP));
        Button confirm = new Button().setText(Component.translatable("folkways.action.confirm"));
        for (ResourceLocation option : options) {
            Button row = new Button().setText(labelOf(option));
            row.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
            ItemStack icon = iconOf(option);
            if (!icon.isEmpty()) {
                row.addPreIcon(new ItemStackTexture(icon));
            }
            Tokens.name(row, "folkways.choice.option." + Tokens.of(option));
            row.setOnClick(event -> {
                picked[0] = option;
                mark(rows, options, picked[0], confirm, current);
            });
            rows.add(row);
            list.addChildren(row);
        }
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(list);
        Tokens.name(scroller, "folkways.choice.options");

        confirm.setOnClick(event -> {
            ResourceLocation chosen = picked[0];
            dialog.close();
            if (chosen != null && !chosen.equals(current)) {
                confirmed.accept(chosen);
            }
        });
        Tokens.name(confirm, "folkways.choice.confirm");
        Button cancel = new Button()
            .setText(Component.translatable("folkways.action.cancel"))
            .setOnClick(event -> dialog.close());
        cancel.addClass("__cancel-button__");
        Tokens.name(cancel, "folkways.choice.cancel");
        mark(rows, options, picked[0], confirm, current);

        dialog.setTitle(titleKey);
        dialog.addContent(new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).heightPercent(100).gapAll(3))
            .addChildren(Rows.text(Component.translatable("folkways.choice.hint")), scroller));
        dialog.addButton(cancel);
        dialog.addButton(confirm);
        Tokens.name(dialog, "folkways.choice");
        float height = CHROME + Math.min(Math.max(options.size(), 1), MOST_ROWS) * (ROW + ROW_GAP);
        place(dialog, host, height);
        int depth = aboveWindows.getAsInt();
        dialog.style(style -> style.zIndex(depth));
        return dialog.show(host.getModularUI());
    }

    private static void place(Dialog dialog, UIElement host, float height) {
        UIElement screen = host;
        while (screen.getParent() != null) {
            screen = screen.getParent();
        }
        float right = host.getPositionX() + host.getSizeWidth() + HOST_GAP;
        float left = host.getPositionX() - HOST_GAP - WIDTH;
        float width = screen.getSizeWidth();
        float tall = screen.getSizeHeight();
        float x = width <= 0f || right + WIDTH <= width ? right : Math.max(0f, left);
        float y = tall <= 0f
            ? host.getPositionY()
            : Math.max(0f, Math.min(host.getPositionY(), tall - height));
        dialog.windowMode(x, y, WIDTH, height);
    }

    private static void mark(List<Button> rows, List<ResourceLocation> options,
            @Nullable ResourceLocation picked, Button confirm, @Nullable ResourceLocation current) {
        for (int at = 0; at < rows.size(); at++) {
            if (options.get(at).equals(picked)) {
                rows.get(at).addClass(CHOSEN);
            } else {
                rows.get(at).removeClass(CHOSEN);
            }
        }
        confirm.setActive(picked != null && !picked.equals(current));
    }

    public static Component labelOf(ResourceLocation option) {
        var item = BuiltInRegistries.ITEM.getOptional(option);
        if (item.isPresent() && item.get() != Items.AIR) {
            return item.get().getDescription();
        }
        var block = BuiltInRegistries.BLOCK.getOptional(option);
        if (block.isPresent()) {
            return block.get().getName();
        }
        var entity = BuiltInRegistries.ENTITY_TYPE.getOptional(option);
        if (entity.isPresent()) {
            return entity.get().getDescription();
        }
        return Component.literal(option.getPath());
    }

    public static ItemStack iconOf(ResourceLocation option) {
        var item = BuiltInRegistries.ITEM.getOptional(option);
        if (item.isPresent() && item.get() != Items.AIR) {
            return new ItemStack(item.get());
        }
        var block = BuiltInRegistries.BLOCK.getOptional(option);
        if (block.isPresent() && block.get().asItem() != Items.AIR) {
            return new ItemStack(block.get().asItem());
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(option).map(SpawnEggItem::byId)
            .map(egg -> new ItemStack(egg)).orElse(ItemStack.EMPTY);
    }

    public static final class Asking {

        private int sequence;
        private String asked = "";

        public Asking(UIElement on, BiConsumer<String, ResourceLocation> received) {
            SimpleBinding<String> binding = DataBindingBuilder.stringC2S(sealed -> {
                String[] parts = sealed.split("\\|", 3);
                ResourceLocation option = parts.length == 3 ? ResourceLocation.tryParse(parts[2]) : null;
                if (option != null && !parts[1].isEmpty()) {
                    received.accept(parts[1], option);
                }
            }).build();
            binding.setRemoteDataSource(IDataSource.of(ignored -> {
            }, () -> asked));
            on.addSyncValue(binding.getSyncValue());
        }

        public void ask(String key, ResourceLocation option) {
            asked = ++sequence + "|" + key + "|" + option;
        }
    }
}
