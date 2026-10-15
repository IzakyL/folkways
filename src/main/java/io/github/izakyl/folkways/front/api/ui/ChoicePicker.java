package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.Schema;
import io.github.izakyl.folkways.front.api.ui.Rows;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    // How high a dialog must sit to show over every window open now.
    public static int depth() {
        return aboveWindows.getAsInt();
    }

    public static Dialog open(UIElement host, Schema.Setting.Choice choice, ResourceLocation current,
            Consumer<ResourceLocation> confirmed) {
        return open(host, choice.nameKey(), choice.options(), current, confirmed);
    }

    public static Dialog open(UIElement host, String titleKey, List<ResourceLocation> options,
            @Nullable ResourceLocation current, Consumer<ResourceLocation> confirmed) {
        List<Row> rows = new ArrayList<>();
        for (ResourceLocation option : options) {
            rows.add(new Row(option.toString(), labelOf(option), iconOf(option)));
        }
        return open(host, Component.translatable(titleKey), rows, current == null ? null : current.toString(),
            picked -> confirmed.accept(ResourceLocation.parse(picked)));
    }

    /** One thing a picker offers: the key handed back when it is picked, the words it reads as, the icon it wears. */
    public record Row(String key, Component label, ItemStack icon) {
    }

    // One of the rows: confirming hands back the key picked, when it is not the one already held.
    public static Dialog open(UIElement host, String title, List<Row> rows, @Nullable String current,
            Consumer<String> confirmed) {
        return open(host, Component.translatable(title), rows, current, confirmed);
    }

    public static Dialog open(UIElement host, Component title, List<Row> rows, @Nullable String current,
            Consumer<String> confirmed) {
        Set<String> held = current == null ? Set.of() : Set.of(current);
        return pick(host, title, rows, held, false,
            picked -> picked.stream().findFirst().ifPresent(confirmed));
    }

    // Any of the rows: confirming hands back every key picked, in the order the rows stand.
    public static Dialog openMany(UIElement host, String title, List<Row> rows, Set<String> current,
            Consumer<List<String>> confirmed) {
        return openMany(host, Component.translatable(title), rows, current, confirmed);
    }

    public static Dialog openMany(UIElement host, Component title, List<Row> rows, Set<String> current,
            Consumer<List<String>> confirmed) {
        return pick(host, title, rows, current, true, confirmed);
    }

    private static Dialog pick(UIElement host, Component title, List<Row> offered, Set<String> current, boolean many,
            Consumer<List<String>> confirmed) {
        Dialog dialog = new Dialog();
        Set<String> picked = new LinkedHashSet<>();
        for (Row row : offered) {
            if (current.contains(row.key())) {
                picked.add(row.key());
            }
        }
        Set<String> held = Set.copyOf(picked);
        List<Button> rows = new ArrayList<>();
        UIElement list = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(ROW_GAP));
        Button confirm = new Button().setText(Component.translatable("folkways.action.confirm"));
        for (Row option : offered) {
            Button row = new Button().setText(option.label());
            row.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
            if (!option.icon().isEmpty()) {
                row.addPreIcon(new ItemStackTexture(option.icon()));
            }
            Tokens.name(row, "folkways.choice.option." + tokenOf(option.key()));
            row.setOnClick(event -> {
                if (!many) {
                    picked.clear();
                    picked.add(option.key());
                } else if (!picked.remove(option.key())) {
                    picked.add(option.key());
                }
                mark(rows, offered, picked, confirm, held);
            });
            rows.add(row);
            list.addChildren(row);
        }
        ScrollerView scroller = Rows.fills(Rows.box());
        scroller.addScrollViewChild(list);
        Tokens.name(scroller, "folkways.choice.options");

        confirm.setOnClick(event -> {
            dialog.close();
            if (!picked.equals(held)) {
                List<String> inOrder = new ArrayList<>();
                for (Row row : offered) {
                    if (picked.contains(row.key())) {
                        inOrder.add(row.key());
                    }
                }
                confirmed.accept(inOrder);
            }
        });
        Tokens.name(confirm, "folkways.choice.confirm");
        Button cancel = new Button()
            .setText(Component.translatable("folkways.action.cancel"))
            .setOnClick(event -> dialog.close());
        cancel.addClass("__cancel-button__");
        Tokens.name(cancel, "folkways.choice.cancel");
        mark(rows, offered, picked, confirm, held);

        dialog.titleBar.addChildren(new Label().setText(title)
            .textStyle(style -> style.textAlignVertical(Vertical.CENTER)
                .textAlignHorizontal(Horizontal.CENTER).adaptiveWidth(true)));
        dialog.addContent(new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).heightPercent(100).gapAll(3))
            .addChildren(Rows.text(Component.translatable(many ? "folkways.choice.hint.many" : "folkways.choice.hint")),
                scroller));
        dialog.addButton(cancel);
        dialog.addButton(confirm);
        Tokens.name(dialog, "folkways.choice");
        float height = CHROME + Math.min(Math.max(offered.size(), 1), MOST_ROWS) * (ROW + ROW_GAP);
        return show(dialog, host, WIDTH, height);
    }

    private static String tokenOf(String key) {
        return key.replace("#", "").replace(':', '.').replace('/', '.');
    }

    // Shows a dialog as a window beside the element that asked for it, over every window already open.
    public static Dialog show(Dialog dialog, UIElement host, float width, float height) {
        place(dialog, host, width, height);
        int depth = aboveWindows.getAsInt();
        dialog.style(style -> style.zIndex(depth));
        return dialog.show(host.getModularUI());
    }

    private static void place(Dialog dialog, UIElement host, float wide, float height) {
        UIElement screen = host;
        while (screen.getParent() != null) {
            screen = screen.getParent();
        }
        float right = host.getPositionX() + host.getSizeWidth() + HOST_GAP;
        float left = host.getPositionX() - HOST_GAP - wide;
        float width = screen.getSizeWidth();
        float tall = Math.max(0f, screen.getSizeHeight() - Rows.FOOT);
        float x = width <= 0f || right + wide <= width ? right : Math.max(0f, left);
        float y = tall <= 0f
            ? host.getPositionY()
            : Math.max(0f, Math.min(host.getPositionY(), tall - height));
        dialog.windowMode(x, y, wide, height);
    }

    private static void mark(List<Button> rows, List<Row> offered, Set<String> picked, Button confirm,
            Set<String> held) {
        for (int at = 0; at < rows.size(); at++) {
            if (picked.contains(offered.get(at).key())) {
                rows.get(at).addClass(CHOSEN);
            } else {
                rows.get(at).removeClass(CHOSEN);
            }
        }
        confirm.setActive(!picked.equals(held));
    }

    // An option written "<id>/<variant>" is another way of the option before the stroke: it reads as that one's
    // name in the variant's words, and wears its icon.
    public static Component labelOf(ResourceLocation option) {
        Component named = nameOf(option);
        if (named != null) {
            return named;
        }
        int stroke = option.getPath().lastIndexOf('/');
        if (stroke > 0) {
            Component whole = nameOf(option.withPath(option.getPath().substring(0, stroke)));
            if (whole != null) {
                return Component.translatable(
                    "folkways.choice.variant." + option.getPath().substring(stroke + 1), whole);
            }
        }
        return Component.literal(option.getPath());
    }

    @Nullable
    private static Component nameOf(ResourceLocation option) {
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
        return null;
    }

    public static ItemStack iconOf(ResourceLocation option) {
        ItemStack icon = wornBy(option);
        int stroke = option.getPath().lastIndexOf('/');
        return icon.isEmpty() && stroke > 0
            ? wornBy(option.withPath(option.getPath().substring(0, stroke)))
            : icon;
    }

    private static ItemStack wornBy(ResourceLocation option) {
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
