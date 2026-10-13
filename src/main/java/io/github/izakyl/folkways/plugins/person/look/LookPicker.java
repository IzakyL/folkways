package io.github.izakyl.folkways.plugins.person.look;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.front.api.ui.ChoicePicker;
import io.github.izakyl.folkways.front.api.ui.Rows;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

// Every look there is, filed in folders, beside a preview of the one the pointer is on. Either each look and
// each folder has a box to tick, or a look is taken by clicking it. Its rows are the client's alone: the client
// knows every look, and tells the server what was done through a Line.
public final class LookPicker {

    public static final String PICK = "pick";
    public static final String ON = "on";
    public static final String OFF = "off";
    public static final String FOLDER_ON = "folder_on";
    public static final String FOLDER_OFF = "folder_off";

    private static final String CHOSEN = "folkways_chosen";

    private static final float ROW = 18f;
    private static final float INDENT = 10f;
    private static final float BOX = 16f;
    private static final float SIDE_WIDTH = 92f;
    private static final float PREVIEW_HEIGHT = 124f;

    private static final float DIALOG_WIDTH = 300f;
    private static final float DIALOG_HEIGHT = 230f;

    private final String token;
    private final boolean ticked;
    private final BiConsumer<String, String> say;

    private final UIElement tree = new UIElement()
        .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
    private final UIElement side = new UIElement();
    private final Label named = Rows.text(Component.empty());
    private final Label filed = Rows.note(Component.empty());
    private Consumer<ResourceLocation> preview = look -> {
    };

    private List<ResourceLocation> filled = List.of();
    private Set<ResourceLocation> marked = Set.of();
    @Nullable
    private ResourceLocation previewed;
    private final Map<ResourceLocation, UIElement> leaves = new LinkedHashMap<>();
    private final List<Shelf> shelves = new ArrayList<>();

    // ticked: looks and folders are switched on and off by their boxes; otherwise a click on a look takes it.
    // say is told what the player did: a word of this class's, and the look's id or the folder's prefix.
    // token heads the names of its elements, so two pickers on one screen are told apart.
    public LookPicker(String token, boolean client, boolean ticked, BiConsumer<String, String> say) {
        this.token = token;
        this.ticked = ticked;
        this.say = say;
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
        Tokens.name(tree, token + ".tree");
    }

    public UIElement tree() {
        return tree;
    }

    public UIElement side() {
        return side;
    }

    // The tree in a box of its own beside the preview, for a window that holds nothing else.
    public UIElement whole() {
        ScrollerView scroller = Rows.box();
        scroller.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).minHeight(0));
        scroller.addScrollViewChild(tree);
        return beside(scroller);
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
        if (sorted.equals(filled)) {
            return;
        }
        Set<String> wasOpen = new HashSet<>();
        shelves.stream().filter(shelf -> shelf.open).forEach(shelf -> wasOpen.add(shelf.folder.prefix()));
        filled = sorted;
        leaves.clear();
        shelves.clear();
        tree.clearAllChildren();
        List<LookTree.Folder> roots = LookTree.of(sorted);
        for (LookTree.Folder root : roots) {
            Shelf shelf = new Shelf(root);
            tree.addChildren(shelf.element);
            if (roots.size() == 1) {
                shelf.open(true);
            }
        }
        reopen(wasOpen);
        if (previewed == null || !sorted.contains(previewed)) {
            show(sorted.isEmpty() ? null : sorted.getFirst());
        }
    }

    private void reopen(Set<String> prefixes) {
        // A shelf opened here lays out the shelves under it, so the list grows as it is walked.
        for (int at = 0; at < shelves.size(); at++) {
            Shelf shelf = shelves.get(at);
            if (prefixes.contains(shelf.folder.prefix())) {
                shelf.open(true);
            }
        }
    }

    // Client only. In a ticked picker these are the looks switched on; otherwise the one look worn.
    public void mark(Set<ResourceLocation> on) {
        marked = Set.copyOf(on);
        leaves.forEach(this::wear);
        shelves.forEach(Shelf::wear);
    }

    // Opens every folder on the way down to a look, and shows it in the preview.
    public void reveal(ResourceLocation look) {
        for (int at = 0; at < shelves.size(); at++) {
            Shelf shelf = shelves.get(at);
            if (shelf.folder.holds(look)) {
                shelf.open(true);
            }
        }
        show(look);
    }

    private void show(@Nullable ResourceLocation look) {
        previewed = look;
        preview.accept(look);
        named.setText(look == null ? Component.empty() : nameOf(look));
        filed.setText(look == null ? Component.empty() : Component.literal(look.toString()));
    }

    private UIElement leaf(ResourceLocation look) {
        UIElement leaf;
        if (ticked) {
            Toggle box = new Toggle().setText(nameOf(look));
            box.setOnToggleChanged(on -> say.accept(on ? ON : OFF, look.toString()));
            // The box alone answers a click of its own accord; the name beside it should too.
            box.toggleLabel(label -> label.textStyle(style -> style.textWrap(TextWrap.HIDE))
                .addEventListener(UIEvents.MOUSE_DOWN, event -> box.setOn(!box.isOn(), true)));
            leaf = box;
        } else {
            Button press = new Button().setText(nameOf(look));
            leftward(press);
            press.setOnClick(event -> {
                mark(Set.of(look));
                show(look);
                say.accept(PICK, look.toString());
            });
            leaf = press;
        }
        leaf.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
        leaf.addEventListener(UIEvents.MOUSE_ENTER, event -> show(look), true);
        Tokens.name(leaf, token + ".look." + Tokens.of(look));
        wear(look, leaf);
        return leaf;
    }

    // A button reads from its left edge, as a line in a list does.
    private static void leftward(Button press) {
        press.layout(layout -> layout.justifyContent(AlignContent.FLEX_START).paddingHorizontal(4));
        press.textStyle(style -> style.textAlignHorizontal(Horizontal.LEFT).textWrap(TextWrap.HIDE));
    }

    private void wear(ResourceLocation look, UIElement leaf) {
        boolean on = marked.contains(look);
        if (leaf instanceof Toggle box) {
            box.setOn(on, false);
        } else if (on) {
            leaf.addClass(CHOSEN);
        } else {
            leaf.removeClass(CHOSEN);
        }
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

    private static Component folderName(LookTree.Folder folder) {
        String fallback = folder.name().contains("/") ? folder.name() : modName(folder.name());
        return Component.translatableWithFallback("folkways.looks.folder." + dotted(folder), fallback);
    }

    // A folder's prefix without the mark that ends it, in the dots a token and a translation key are written in.
    private static String dotted(LookTree.Folder folder) {
        String prefix = folder.prefix();
        return prefix.substring(0, prefix.length() - 1).replace(':', '.').replace('/', '.');
    }

    private static String modName(String namespace) {
        ModList mods = ModList.get();
        return mods != null && mods.isLoaded(namespace)
            ? mods.getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace)
            : namespace;
    }

    // One folder: its line, and under it, once opened, its folders and looks.
    private final class Shelf {
        private final LookTree.Folder folder;
        private final List<ResourceLocation> all;
        private final UIElement element = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
        private final UIElement under = new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100)
                .paddingLeft(INDENT).gapAll(1));
        private final Button head = new Button();
        @Nullable
        private final Toggle box;
        private boolean open;
        private boolean laid;

        private Shelf(LookTree.Folder folder) {
            this.folder = folder;
            this.all = folder.all();
            shelves.add(this);
            leftward(head);
            head.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).height(ROW));
            head.setOnClick(event -> open(!open));
            String named = dotted(folder);
            Tokens.name(head, token + ".folder." + named);
            UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).height(ROW).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2));
            if (ticked) {
                box = new Toggle().noText();
                box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
                box.setOnToggleChanged(on -> say.accept(on ? FOLDER_ON : FOLDER_OFF, folder.prefix()));
                box.style(style -> style.tooltips(Component.translatable("folkways.looks.folder.toggle")));
                Tokens.name(box, token + ".tick." + named);
                line.addChildren(box);
            } else {
                box = null;
            }
            line.addChildren(head);
            under.setDisplay(false);
            element.addChildren(line, under);
            wear();
        }

        private void open(boolean opened) {
            open = opened;
            if (opened && !laid) {
                laid = true;
                for (LookTree.Folder inner : folder.folders()) {
                    under.addChildren(new Shelf(inner).element);
                }
                for (ResourceLocation look : folder.looks()) {
                    UIElement leaf = leaf(look);
                    leaves.put(look, leaf);
                    under.addChildren(leaf);
                }
            }
            under.setDisplay(opened);
            wear();
        }

        private void wear() {
            long on = all.stream().filter(marked::contains).count();
            MutableComponent text = Component.literal(open ? "- " : "+ ").append(folderName(folder));
            if (ticked) {
                text.append(Component.literal("  " + on + "/" + all.size()).withColor(Rows.QUIET & 0xFFFFFF));
                box.setOn(on == all.size() && !all.isEmpty(), false);
            } else if (on > 0) {
                text.append(Component.literal("  •").withColor(Rows.QUIET & 0xFFFFFF));
            }
            head.setText(text);
        }
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
