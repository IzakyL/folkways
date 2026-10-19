package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.FolderPicker;
import io.github.izakyl.folkways.front.api.ui.IdTree;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

// Every look there is, filed in folders with a search over them, beside a preview of the one the pointer is on.
// Either each look and each folder has a box to tick, or a look is taken by clicking it. Its rows are the client's
// alone: the client knows every look, and tells the server what was done through a Line.
public final class LookPicker {

    public static final String PICK = FolderPicker.PICK;
    public static final String ON = FolderPicker.ON;
    public static final String OFF = FolderPicker.OFF;
    public static final String FOLDER_ON = FolderPicker.FOLDER_ON;
    public static final String FOLDER_OFF = FolderPicker.FOLDER_OFF;

    private static final float SIDE_WIDTH = 92f;
    private static final float PREVIEW_HEIGHT = 124f;

    private static final float DIALOG_WIDTH = 300f;
    private static final float DIALOG_HEIGHT = 230f;

    private final String token;
    private final FolderPicker picker;
    private final UIElement side = new UIElement();
    private final Label named = Rows.text(Component.empty());
    private final Label filed = Rows.note(Component.empty());
    private Consumer<ResourceLocation> preview = look -> {
    };

    @Nullable
    private ResourceLocation previewed;

    // ticked: looks and folders are switched on and off by their boxes; otherwise a click on a look takes it.
    // say is told what the player did: a word of this class's, and the look's id or the folder's prefix.
    // token heads the names of its elements, so two pickers on one screen are told apart.
    public LookPicker(String token, boolean client, boolean ticked, BiConsumer<String, String> say) {
        this.token = token;
        this.picker = new FolderPicker(token, ticked, (word, what) -> {
            if (word.equals(PICK)) {
                show(ResourceLocation.tryParse(what));
            }
            say.accept(word, what);
        }).naming(LookPicker::folderName).hovered(look -> show(ResourceLocation.tryParse(look)));
        UIElement box = new UIElement()
            .layout(layout -> layout.widthPercent(100).height(PREVIEW_HEIGHT).flexShrink(0))
            .addClass("preview_bg");
        box.setOverflowVisible(false);
        Tokens.name(box, token + ".preview");
        UIElement drawn = new UIElement().layout(layout -> layout.widthPercent(100).heightPercent(100));
        box.addChildren(drawn);
        if (client) {
            preview = LookPreview.in(drawn);
        }
        Tokens.name(named, token + ".previewed");
        side.layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .width(SIDE_WIDTH).flexShrink(0).gapAll(3));
        side.addChildren(box, named, filed);
    }

    public static Optional<Component> disabledReason(List<ResourceLocation> looks) {
        return looks.stream().flatMap(look -> LookPreview.disabledReason(look).stream()).findFirst();
    }

    public UIElement tree() {
        return picker.tree();
    }

    public UIElement side() {
        return side;
    }

    // The search over a box that scrolls the tree among whatever else it holds.
    public UIElement searching(ScrollerView scroller) {
        return picker.searching(scroller);
    }

    // The tree in a box of its own beside the preview, for a window that holds nothing else.
    public UIElement whole() {
        return beside(picker.list());
    }

    public UIElement beside(UIElement list) {
        UIElement whole = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).flexGrow(1).flexShrink(1).minHeight(0).gapAll(Rows.GAP))
            .addChildren(list, side);
        whole.setOverflowVisible(false);
        return whole;
    }

    // Client only. Lays the folders out afresh when the looks there are have changed; open folders stay open.
    public void fill() {
        List<ResourceLocation> sorted = LookPreview.known().stream().sorted().toList();
        picker.fill(sorted.stream().map(look -> new FolderPicker.Leaf(look.toString(), look.toString(),
            nameOf(look), token + ".look." + Tokens.of(look), LookPreview.disabledReason(look))).toList());
        if (previewed == null || !sorted.contains(previewed)) {
            show(sorted.isEmpty() ? null : sorted.getFirst());
        }
    }

    // Client only. In a ticked picker these are the looks switched on; otherwise the one look worn.
    public void mark(Set<ResourceLocation> on) {
        picker.mark(on.stream().map(ResourceLocation::toString).collect(Collectors.toSet()));
    }

    // Opens every folder on the way down to a look, and shows it in the preview.
    public void reveal(ResourceLocation look) {
        picker.reveal(look.toString());
        show(look);
    }

    private void show(@Nullable ResourceLocation look) {
        previewed = look;
        preview.accept(look);
        named.setText(look == null ? Component.empty() : nameOf(look));
        filed.setText(look == null ? Component.empty() : Component.literal(look.toString()));
    }

    // What a look is called: its own translation where a pack gives one, a maid's name where it is a maid.
    public static Component nameOf(ResourceLocation look) {
        String path = look.getPath();
        String last = path.substring(path.lastIndexOf('/') + 1);
        String fallback = MaidLooks.nameKey(look)
            .map(key -> Component.translatableWithFallback(key, last).getString())
            .orElse(last);
        return Component.translatableWithFallback("folkways.looks.name." + Tokens.of(look), fallback);
    }

    private static Component folderName(IdTree.Folder<FolderPicker.Leaf> folder) {
        String fallback = folder.name().contains("/") ? folder.name() : modName(folder.name());
        return Component.translatableWithFallback("folkways.looks.folder." + folder.dotted(), fallback);
    }

    private static String modName(String namespace) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(namespace)
            ? mods.getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace)
            : namespace;
    }

    // Opens the picker as a window of its own beside whatever asked for it; a click on a look is told to taken.
    public static Open open(UIElement host, @Nullable ResourceLocation worn, Consumer<ResourceLocation> taken) {
        Dialog dialog = new Dialog();
        LookPicker picker = new LookPicker("folkways.looks", true, false, (word, look) -> {
            ResourceLocation id = ResourceLocation.tryParse(look);
            if (id != null) {
                taken.accept(id);
            }
        });
        picker.fill();
        Button close = new Button()
            .setText(Component.translatable("folkways.selector.close"))
            .setOnClick(event -> dialog.close());
        close.addClass("__cancel-button__");
        Tokens.name(close, "folkways.looks.close");
        dialog.setTitle("folkways.looks.pick");
        dialog.addContent(new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).heightPercent(100).gapAll(3))
            .addChildren(picker.whole()));
        dialog.addButton(close);
        dialog.setClickOutsideClose(false);
        Tokens.name(dialog, "folkways.looks.picker");
        ChoicePicker.show(dialog, host, DIALOG_WIDTH, DIALOG_HEIGHT);
        Open open = new Open(dialog, picker);
        open.wears(worn);
        if (worn != null) {
            picker.reveal(worn);
        }
        return open;
    }

    public static final class Open {
        private final Dialog dialog;
        private final LookPicker picker;
        private boolean closed;

        private Open(Dialog dialog, LookPicker picker) {
            this.dialog = dialog;
            this.picker = picker;
            dialog.setOnClose(() -> closed = true);
        }

        public boolean isOpen() {
            return !closed;
        }

        public void wears(@Nullable ResourceLocation worn) {
            picker.mark(worn == null ? Set.of() : Set.of(worn));
        }

        public void close() {
            if (!closed) {
                dialog.close();
            }
        }
    }

    // What the client's rows tell the server. The last few things said ride along each time, numbered, so two
    // clicks inside one tick both arrive.
    public static final class Line {
        private static final int KEPT = 8;

        private final Deque<String> recent = new ArrayDeque<>();
        private int said;
        private int heard;
        private String sent = "";

        public Line(UIElement on, BiConsumer<String, String> hearing) {
            SimpleBinding<String> binding = DataBindingBuilder.stringC2S(sealed -> {
                for (String one : sealed.split("\n")) {
                    String[] parts = one.split("\\|", 3);
                    int number = parts.length == 3 ? numbered(parts[0]) : 0;
                    if (number > heard) {
                        heard = number;
                        hearing.accept(parts[1], parts[2]);
                    }
                }
            }).build();
            binding.setRemoteDataSource(IDataSource.of(ignored -> {
            }, () -> sent));
            on.addSyncValue(binding.getSyncValue());
        }

        public void say(String word, String what) {
            recent.addLast(++said + "|" + word + "|" + what);
            if (recent.size() > KEPT) {
                recent.removeFirst();
            }
            sent = String.join("\n", recent);
        }

        private static int numbered(String written) {
            try {
                return Integer.parseInt(written);
            } catch (NumberFormatException exception) {
                return 0;
            }
        }
    }
}
