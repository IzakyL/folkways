package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

// Things filed in folders by where they are kept, with a search over them all. Either each thing and each folder
// has a box to tick, or a thing is taken by clicking it. say is told what the player did: a word of this class's,
// and the thing's key or the folder's prefix.
public final class FolderPicker {

    public static final String PICK = "pick";
    public static final String ON = "on";
    public static final String OFF = "off";
    public static final String FOLDER_ON = "folder_on";
    public static final String FOLDER_OFF = "folder_off";

    private static final String CHOSEN = "folkways_chosen";
    private static final String ROOT = "folkways.picker.root.";

    private static final float ROW = 18f;
    private static final float INDENT = 10f;
    private static final float BOX = 16f;
    // A search that finds no more than this opens every folder it found them in; one that finds more, only the
    // folders at the top.
    private static final int UNFOLDED_AT_MOST = 40;

    /**
     * One thing filed: the key told back when it is picked, where it is kept ("root:some/path/name"), the words it
     * reads as, and the token its row is named by.
     */
    public record Leaf(String key, String filed, Component name, String token, Optional<Component> disabledReason) {
        public Leaf(String key, String filed, Component name, String token) {
            this(key, filed, name, token, Optional.empty());
        }
    }

    private final String token;
    private final boolean ticked;
    private final BiConsumer<String, String> say;
    private Function<IdTree.Folder<Leaf>, Component> naming = FolderPicker::rootNamed;
    private Consumer<String> hovered = key -> {
    };
    private boolean openRoots;

    private final TextField search = new TextField();
    private final UIElement tree = column();
    private List<Leaf> filled = List.of();
    private Set<String> marked = Set.of();
    private String query = "";
    // The folders that stood open before a search began, opened again once it is cleared.
    private Set<String> openBefore = Set.of();
    private final Map<String, UIElement> leaves = new LinkedHashMap<>();
    private final List<Shelf> shelves = new ArrayList<>();

    // token heads the names of its elements, so two pickers on one screen are told apart.
    public FolderPicker(String token, boolean ticked, BiConsumer<String, String> say) {
        this.token = token;
        this.ticked = ticked;
        this.say = say;
        search.layout(layout -> layout.widthPercent(100).flexShrink(0));
        search.textFieldStyle(style -> style.placeholder(Component.translatable("folkways.picker.search")));
        search.setTextResponder(typed -> searched(typed == null ? "" : typed.trim()));
        Tokens.name(search, token + ".search");
        Tokens.name(tree, token + ".tree");
    }

    // How a folder reads; by default a root reads as its words under folkways.picker.root, the rest as they are kept.
    public FolderPicker naming(Function<IdTree.Folder<Leaf>, Component> named) {
        naming = named;
        return this;
    }

    // Told the key of each thing the pointer comes onto.
    public FolderPicker hovered(Consumer<String> onto) {
        hovered = onto;
        return this;
    }

    // The folders at the top stand open from the first, however many there are.
    public FolderPicker openRoots() {
        openRoots = true;
        return this;
    }

    public UIElement search() {
        return search;
    }

    public UIElement tree() {
        return tree;
    }

    // The search over a box that scrolls the tree, for a list that holds nothing else.
    public UIElement list() {
        ScrollerView scroller = Rows.box();
        scroller.addScrollViewChild(tree);
        return searching(scroller);
    }

    // The search over a box that scrolls the tree among whatever else it holds.
    public UIElement searching(ScrollerView scroller) {
        Rows.fills(scroller);
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).minHeight(0).gapAll(3))
            .addChildren(search, scroller);
    }

    // Lays the folders out afresh when what is filed has changed; open folders stay open.
    public void fill(List<Leaf> filing) {
        if (filing.equals(filled)) {
            return;
        }
        Set<String> wasOpen = opened();
        filled = List.copyOf(filing);
        lay(wasOpen);
    }

    public List<Leaf> filled() {
        return filled;
    }

    // In a ticked picker these are the things switched on; otherwise the one thing picked.
    public void mark(Set<String> on) {
        marked = Set.copyOf(on);
        leaves.forEach(this::wear);
        shelves.forEach(Shelf::wear);
    }

    // Opens every folder on the way down to a thing.
    public void reveal(String key) {
        filled.stream().filter(leaf -> leaf.key().equals(key)).findFirst().ifPresent(leaf -> {
            for (int at = 0; at < shelves.size(); at++) {
                Shelf shelf = shelves.get(at);
                if (shelf.folder.holds(leaf.filed())) {
                    shelf.open(true);
                }
            }
        });
    }

    private Set<String> opened() {
        Set<String> open = new HashSet<>();
        shelves.stream().filter(shelf -> shelf.open).forEach(shelf -> open.add(shelf.folder.prefix()));
        return open;
    }

    private void searched(String typed) {
        if (typed.equals(query)) {
            return;
        }
        if (query.isEmpty()) {
            openBefore = opened();
        }
        query = typed;
        lay(query.isEmpty() ? openBefore : Set.of());
    }

    // What a search finds is filed as the whole tree is: a folder holds only what was found in it, so a folder
    // that found nothing is not there at all. A thing is found by its words or where it is kept, and everything
    // in a folder is found when the folder's own words are.
    private void lay(Set<String> open) {
        leaves.clear();
        shelves.clear();
        tree.clearAllChildren();
        boolean searching = !query.isEmpty();
        List<Leaf> shown = searching ? found() : filled;
        if (shown.isEmpty()) {
            if (searching) {
                tree.addChildren(Rows.note(Component.translatable("folkways.picker.none")));
            }
            return;
        }
        List<IdTree.Folder<Leaf>> roots = IdTree.of(shown, Leaf::filed);
        for (IdTree.Folder<Leaf> root : roots) {
            Shelf shelf = new Shelf(root);
            tree.addChildren(shelf.element);
            if (openRoots || searching || roots.size() == 1) {
                shelf.open(true);
            }
        }
        boolean unfolded = searching && shown.size() <= UNFOLDED_AT_MOST;
        // A shelf opened here lays out the shelves under it, so the list grows as it is walked.
        for (int at = 0; at < shelves.size(); at++) {
            Shelf shelf = shelves.get(at);
            if (unfolded || open.contains(shelf.folder.prefix())) {
                shelf.open(true);
            }
        }
    }

    private List<Leaf> found() {
        String asked = query.toLowerCase(Locale.ROOT);
        List<String> named = new ArrayList<>();
        List<IdTree.Folder<Leaf>> walking = new ArrayList<>(IdTree.of(filled, Leaf::filed));
        while (!walking.isEmpty()) {
            IdTree.Folder<Leaf> folder = walking.removeLast();
            if (naming.apply(folder).getString().toLowerCase(Locale.ROOT).contains(asked)) {
                named.add(folder.prefix());
            }
            walking.addAll(folder.folders());
        }
        return filled.stream().filter(leaf -> leaf.name().getString().toLowerCase(Locale.ROOT).contains(asked)
            || leaf.filed().toLowerCase(Locale.ROOT).contains(asked)
            || named.stream().anyMatch(prefix -> IdTree.under(prefix, leaf.filed()))).toList();
    }

    private UIElement leaf(Leaf leaf) {
        UIElement row;
        if (ticked) {
            Toggle box = new Toggle().setText(leaf.name());
            box.setOnToggleChanged(on -> {
                if (leaf.disabledReason().isEmpty()) {
                    say.accept(on ? ON : OFF, leaf.key());
                }
            });
            // The box alone answers a click of its own accord; the name beside it should too.
            box.toggleLabel(label -> label.textStyle(style -> style.textWrap(TextWrap.HIDE))
                .addEventListener(UIEvents.MOUSE_DOWN, event -> {
                    if (leaf.disabledReason().isEmpty()) {
                        box.setOn(!box.isOn(), true);
                    }
                }));
            row = box;
        } else {
            Button press = new Button().setText(leaf.name());
            leftward(press);
            press.setOnClick(event -> {
                if (leaf.disabledReason().isPresent()) {
                    return;
                }
                mark(Set.of(leaf.key()));
                say.accept(PICK, leaf.key());
            });
            row = press;
        }
        leaf.disabledReason().ifPresent(reason -> {
            row.setActive(false);
            row.style(style -> style.tooltips(reason));
            if (row instanceof Toggle box) {
                box.toggleLabel(label -> label.textStyle(style -> style.textColor(0xFF8F7F62)));
            }
        });
        row.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
        row.addEventListener(UIEvents.MOUSE_ENTER, event -> hovered.accept(leaf.key()), true);
        Tokens.name(row, leaf.token());
        leaves.put(leaf.key(), row);
        wear(leaf.key(), row);
        return row;
    }

    // A button reads from its left edge, as a line in a list does.
    private static void leftward(Button press) {
        press.layout(layout -> layout.justifyContent(AlignContent.FLEX_START).paddingHorizontal(4));
        press.textStyle(style -> style.textAlignHorizontal(Horizontal.LEFT).textWrap(TextWrap.HIDE));
    }

    private void wear(String key, UIElement row) {
        boolean on = marked.contains(key);
        if (row instanceof Toggle box) {
            box.setOn(on, false);
        } else if (on) {
            row.addClass(CHOSEN);
        } else {
            row.removeClass(CHOSEN);
        }
    }

    // A root reads as its words under folkways.picker.root, the folders run into it as they are kept.
    public static Component rootNamed(IdTree.Folder<?> folder) {
        if (!folder.root()) {
            return Component.literal(folder.name());
        }
        String name = folder.name();
        int stroke = name.indexOf('/');
        String root = stroke < 0 ? name : name.substring(0, stroke);
        MutableComponent named = Component.translatableWithFallback(ROOT + root, root).copy();
        return stroke < 0 ? named : named.append(name.substring(stroke));
    }

    private static UIElement column() {
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
    }

    // One folder: its line, and under it, once opened, its folders and things.
    private final class Shelf {
        private final IdTree.Folder<Leaf> folder;
        private final List<String> all;
        private final UIElement element = column();
        private final UIElement under = column();
        private final Button head = new Button();
        private final Toggle box;
        private boolean open;
        private boolean laid;

        private Shelf(IdTree.Folder<Leaf> folder) {
            this.folder = folder;
            this.all = folder.all().stream().map(Leaf::key).toList();
            shelves.add(this);
            leftward(head);
            head.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).height(ROW));
            head.setOnClick(event -> open(!open));
            String named = folder.dotted();
            Tokens.name(head, token + ".folder." + named);
            UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).height(ROW).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2));
            if (ticked) {
                box = new Toggle().noText();
                box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
                Optional<Component> disabled = folder.all().stream()
                    .flatMap(leaf -> leaf.disabledReason().stream()).findFirst();
                box.setOnToggleChanged(on -> {
                    if (disabled.isEmpty()) {
                        say.accept(on ? FOLDER_ON : FOLDER_OFF, folder.prefix());
                    }
                });
                disabled.ifPresent(reason -> {
                    box.setActive(false);
                    box.style(style -> style.tooltips(reason));
                });
                Tokens.name(box, token + ".tick." + named);
                line.addChildren(box);
            } else {
                box = null;
            }
            line.addChildren(head);
            under.layout(layout -> layout.paddingLeft(INDENT));
            under.setDisplay(false);
            element.addChildren(line, under);
            wear();
        }

        private void open(boolean opened) {
            open = opened;
            if (opened && !laid) {
                laid = true;
                for (IdTree.Folder<Leaf> inner : folder.folders()) {
                    under.addChildren(new Shelf(inner).element);
                }
                for (Leaf leaf : folder.leaves()) {
                    under.addChildren(leaf(leaf));
                }
            }
            under.setDisplay(opened);
            wear();
        }

        private void wear() {
            long on = all.stream().filter(marked::contains).count();
            MutableComponent text = Component.literal(open ? "- " : "+ ").append(naming.apply(folder));
            if (ticked) {
                text.append(Component.literal("  " + on + "/" + all.size()).withColor(Rows.QUIET & 0xFFFFFF));
                box.setOn(on == all.size() && !all.isEmpty(), false);
            } else if (on > 0) {
                text.append(Component.literal("  •").withColor(Rows.QUIET & 0xFFFFFF));
            }
            head.setText(text);
        }
    }
}
