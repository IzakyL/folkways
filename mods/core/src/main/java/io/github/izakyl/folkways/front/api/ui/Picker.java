package io.github.izakyl.folkways.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.data.Horizontal;
import com.lowdragmc.lowdraglib2.gui.ui.data.TextWrap;
import com.lowdragmc.lowdraglib2.gui.ui.data.Vertical;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.elements.ScrollerView;
import com.lowdragmc.lowdraglib2.gui.ui.elements.TextField;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Toggle;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEventDispatcher;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.terms.OptionQuery;
import io.github.izakyl.folkways.front.api.Schema;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.jetbrains.annotations.Nullable;

// Things filed in folders by where they are kept, with a search over them all. Either each thing and each folder
// has a box to tick, or a thing is taken by clicking it. say is told what the player did: a word of this class's,
// and the thing's key or the folder's prefix.
public final class Picker {

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
    public record Leaf(String key, String filed, Component name, String token, Optional<Component> disabledReason,
            ItemStack icon, String source, List<String> tags) {
        public Leaf(String key, String filed, Component name, String token) {
            this(key, filed, name, token, Optional.empty());
        }

        public Leaf(String key, String filed, Component name, String token, Optional<Component> disabledReason) {
            this(key, filed, name, token, disabledReason, iconOfKey(key), sourceOf(key, filed), List.of());
        }
    }

    private static String sourceOf(String key, String filed) {
        int colon = key.indexOf(':');
        return colon >= 0 ? key.substring(0, colon) : filed.substring(0, Math.max(0, filed.indexOf(':')));
    }

    // Only a key written with its namespace names a registry entry: a bare word such as "chest" is a menu's own,
    // and would otherwise wear a vanilla item it only shares a path with. What names nothing wears nothing: its row
    // keeps the icon's place blank.
    private static ItemStack iconOfKey(String key) {
        ResourceLocation id = key.indexOf(':') < 0 ? null : ResourceLocation.tryParse(key);
        return id == null ? ItemStack.EMPTY : iconOf(id);
    }

    private static IntSupplier aboveWindows = () -> 0;
    private static final float HOST_GAP = 4f;
    private static final int LAID_AT_ONCE = 120;
    private static final int FOLDERS_AT_ONCE = 40;

    private final Button confirm = new Button().setText(Component.translatable("folkways.action.confirm"));
    private Set<String> held = Set.of();
    private boolean confirmUnchanged;
    private boolean immediate;
    private boolean closeOnPick = true;
    private boolean outsideClose;
    private Component empty = Component.translatable("folkways.picker.empty");
    private float width = 270;
    private float height = 340;
    private UIElement side;
    private UIElement extra;
    private UIElement searchAction;
    private boolean compact = true;
    private boolean searchFolders = true;
    private final Map<String, String> names = new LinkedHashMap<>();
    private Dialog dialog;
    private Consumer<List<String>> confirmed = picked -> {
    };
    private Consumer<String> searching = typed -> {
    };
    private Comparator<IdTree.Folder<Leaf>> ordering = Comparator.comparing(IdTree.Folder::name);
    private List<IdTree.Folder<Leaf>> folders = List.of();
    private Function<IdTree.Folder<Leaf>, String> folderTokens = folder -> folder.dotted();
    private BiConsumer<String, Boolean> folderChanged;
    private Predicate<String> folderMarked;
    private Predicate<String> folderSelectable = prefix -> true;
    private OptionQuery.Known known = new OptionQuery.Known(Set.of(), Set.of(), Set.of(), Set.of());

    private final String token;
    private final boolean ticked;
    private final BiConsumer<String, String> say;
    private Function<IdTree.Folder<Leaf>, Component> naming = Picker::rootNamed;
    private Function<Leaf, Component[]> tooltips = leaf -> new Component[] {Component.literal(leaf.key())};
    private Consumer<String> hovered = key -> {
    };
    private boolean openRoots;

    private final TextField search = new TextField();
    private final UIElement tree = column();
    private final UIElement results = column();
    private List<Leaf> filled = List.of();
    private Set<String> marked = Set.of();
    private Set<String> covered;
    private final Set<String> revealing = new HashSet<>();
    private final Set<String> revealedKeys = new HashSet<>();
    private String query = "";
    // The folders that stood open before a search began, opened again once it is cleared.
    private Set<String> openBefore = Set.of();
    private final Map<String, List<UIElement>> leaves = new LinkedHashMap<>();
    private final List<Shelf> shelves = new ArrayList<>();

    // token heads the names of its elements, so two pickers on one screen are told apart.
    public Picker(String token, boolean ticked, BiConsumer<String, String> say) {
        this.token = token;
        this.ticked = ticked;
        this.say = (word, key) -> {
            Set<String> next = new LinkedHashSet<>(marked);
            if (word.equals(PICK)) {
                next.clear();
                next.add(key);
            } else if (word.equals(ON)) {
                next.add(key);
            } else if (word.equals(OFF)) {
                next.remove(key);
            } else if (folderChanged != null) {
                folderChanged.accept(key, word.equals(FOLDER_ON));
            } else {
                filled.stream().filter(leaf -> IdTree.under(key, leaf.filed()) && leaf.disabledReason().isEmpty())
                    .forEach(leaf -> {
                        if (word.equals(FOLDER_ON)) {
                            next.add(leaf.key());
                        } else {
                            next.remove(leaf.key());
                        }
                    });
            }
            if (folderChanged == null || word.equals(PICK) || word.equals(ON) || word.equals(OFF)) {
                mark(next);
            }
            say.accept(word, key);
            if (immediate && word.equals(PICK)) {
                if (closeOnPick) {
                    dialog.close();
                }
                confirmed.accept(List.of(key));
            }
        };
        search.layout(layout -> layout.widthPercent(100).flexShrink(0));
        search.textFieldStyle(style -> style.placeholder(
            Rows.placeholder(Component.translatable("folkways.picker.search"))));
        search.setTextResponder(typed -> searched(typed == null ? "" : typed.trim()));
        search.style(style -> style.tooltips(List.of("mod", "tag", "id", "and", "or").stream()
            .map(word -> Component.translatable("folkways.selector.tooltip." + word)).toArray(Component[]::new)));
        Tokens.name(search, token + ".search");
        Tokens.name(tree, token + ".tree");
        Tokens.name(results, token + ".results");
        results.setDisplay(false);
    }

    // How a folder reads; by default a root reads as its words under folkways.picker.root, the rest as they are kept.
    public Picker naming(Function<IdTree.Folder<Leaf>, Component> named) {
        naming = named;
        return this;
    }

    public Picker tooltips(Function<Leaf, Component[]> words) {
        tooltips = words;
        return this;
    }

    // Told the key of each thing the pointer comes onto.
    public Picker hovered(Consumer<String> onto) {
        hovered = onto;
        return this;
    }

    // The folders at the top stand open from the first, however many there are.
    public Picker openRoots() {
        openRoots = true;
        return this;
    }

    public UIElement search() {
        return search;
    }

    public UIElement tree() {
        return tree;
    }

    public UIElement results() {
        return results;
    }

    // The search over a box that scrolls the tree, for a list that holds nothing else. What a search finds is laid
    // out beside the tree, which it hides while the search stands.
    public UIElement list() {
        ScrollerView scroller = Rows.box();
        scroller.addScrollViewChild(tree);
        scroller.addScrollViewChild(results);
        return searching(scroller);
    }

    // The search over a box that scrolls the tree among whatever else it holds; the box must hold results() too.
    public UIElement searching(ScrollerView scroller) {
        Rows.fills(scroller);
        UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexShrink(0).gapAll(3));
        search.layout(layout -> layout.width(0).flexGrow(1).flexShrink(1).minWidth(0));
        line.addChildren(search);
        if (searchAction != null) {
            line.addChildren(searchAction);
        }
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .flexGrow(1).flexShrink(1).minWidth(0).minHeight(0).gapAll(3))
            .addChildren(line, scroller);
    }

    // Lays the folders out afresh when what is filed has changed; open folders stay open.
    public void fill(List<Leaf> filing) {
        // Nothing filed is still laid once, so an empty dialog says it has nothing to offer.
        if (filing.equals(filled) && !filing.isEmpty()) {
            return;
        }
        Set<String> wasOpen = opened();
        filled = List.copyOf(filing);
        names.clear();
        filled.forEach(leaf -> names.put(leaf.key(), leaf.name().getString().toLowerCase(Locale.ROOT)));
        Set<String> sources = new HashSet<>();
        Set<String> ids = new HashSet<>();
        Set<String> tags = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (Leaf leaf : filled) {
            sources.add(leaf.source());
            ids.add(leaf.key());
            tags.addAll(leaf.tags());
            leaf.tags().forEach(tag -> paths.add(tag.substring(tag.indexOf(':') + 1)));
        }
        known = new OptionQuery.Known(Set.copyOf(sources), Set.copyOf(ids), Set.copyOf(tags), Set.copyOf(paths));
        folders = IdTree.of(filled.stream().filter(leaf -> !leaf.filed().isEmpty()).toList(), Leaf::filed, compact);
        lay(wasOpen);
    }

    public List<Leaf> filled() {
        return filled;
    }

    // In a ticked picker these are the things switched on; otherwise the one thing picked.
    public void mark(Set<String> on) {
        marked = Set.copyOf(on);
        leaves.forEach((key, rows) -> rows.forEach(row -> wear(key, row)));
        shelves.forEach(Shelf::wear);
        confirm.setActive((confirmUnchanged || !marked.equals(held)) && (ticked || !marked.isEmpty()));
    }

    // Opens every folder on the way down to a thing.
    public void reveal(String key) {
        List<Leaf> wanted = filled.stream().filter(leaf -> leaf.key().equals(key)).toList();
        revealedKeys.add(key);
        wanted.forEach(leaf -> revealing.add(leaf.filed()));
        lay(opened());
        // Opening a shelf adds its children to shelves; walk them too, including any pages the held thing needs.
        for (int at = 0; at < shelves.size(); at++) {
            Shelf shelf = shelves.get(at);
            if (wanted.stream().anyMatch(leaf -> shelf.folder.holds(leaf.filed()))) {
                shelf.open(true);
            }
        }
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
        searching.accept(query);
    }

    // What a search finds is filed as the whole tree is: a folder holds only what was found in it, so a folder
    // that found nothing is not there at all. A thing is found by its words or where it is kept, and everything
    // in a folder is found when the folder's own words are.
    private void lay(Set<String> open) {
        leaves.clear();
        shelves.clear();
        tree.clearAllChildren();
        results.clearAllChildren();
        boolean searching = !query.isEmpty();
        tree.setDisplay(!searching);
        results.setDisplay(searching);
        UIElement into = searching ? results : tree;
        List<Leaf> shown = searching ? found() : filled;
        // An empty dialog says so, rather than standing blank under its search field.
        if (shown.isEmpty()) {
            into.addChildren(Rows.note(searching ? Component.translatable("folkways.picker.none") : empty));
            return;
        }
        List<Leaf> flat = shown.stream().filter(leaf -> leaf.filed().isEmpty()).toList();
        layLeaves(into, flat, 0);
        List<IdTree.Folder<Leaf>> roots = searching ? IdTree.of(shown.stream()
            .filter(leaf -> !leaf.filed().isEmpty()).toList(), Leaf::filed, compact) : folders;
        for (IdTree.Folder<Leaf> root : roots) {
            Shelf shelf = new Shelf(root);
            into.addChildren(shelf.element);
            if (openRoots || searching || roots.size() == 1) {
                shelf.open(true);
            }
        }
        boolean unfolded = searching && shown.stream().map(Leaf::key).distinct().count() <= UNFOLDED_AT_MOST;
        // A shelf opened here lays out the shelves under it, so the list grows as it is walked.
        for (int at = 0; at < shelves.size(); at++) {
            Shelf shelf = shelves.get(at);
            if (unfolded || open.contains(shelf.folder.prefix())) {
                shelf.open(true);
            }
        }
    }

    private List<Leaf> found() {
        Optional<OptionQuery> asked = OptionQuery.parse(query);
        // A query not yet whole (a bare # or @) finds nothing rather than everything.
        if (asked.isEmpty()) {
            return List.of();
        }
        Set<String> found = new HashSet<>();
        String literal = query.toLowerCase(Locale.ROOT);
        boolean aliases = searchFolders && !known.items().contains(literal)
            && literal.chars().noneMatch(letter -> "@#*?|".indexOf(letter) >= 0);
        List<IdTree.Folder<Leaf>> walking = new ArrayList<>(aliases ? folders : List.of());
        while (!walking.isEmpty()) {
            IdTree.Folder<Leaf> folder = walking.removeLast();
            String name = naming.apply(folder).getString().toLowerCase(Locale.ROOT);
            if (TextSearch.contains(name, query.toLowerCase(Locale.ROOT))) {
                folder.all().forEach(leaf -> found.add(leaf.key()));
            } else {
                walking.addAll(folder.folders());
            }
        }
        Set<String> tested = new HashSet<>();
        for (Leaf leaf : filled) {
            if (found.contains(leaf.key())) {
                continue;
            }
            // An option may stand under many tags; its query is still asked only once.
            if (tested.add(leaf.key()) && asked.get().matches(known, leaf.key(), leaf.source(), leaf.tags(),
                    names.get(leaf.key()), TextSearch::contains)
                    || aliases && leaf.filed().toLowerCase(Locale.ROOT).contains(literal)) {
                found.add(leaf.key());
            }
        }
        return filled.stream().filter(leaf -> found.contains(leaf.key())).toList();
    }

    private void layLeaves(UIElement into, List<Leaf> filing, int start) {
        int end = Math.min(start + LAID_AT_ONCE, filing.size());
        for (int at = end; at < filing.size(); at++) {
            if (revealedKeys.contains(filing.get(at).key())) {
                end = at + 1;
            }
        }
        int next = end;
        for (int at = start; at < end; at++) {
            into.addChildren(leaf(filing.get(at)));
        }
        if (end < filing.size()) {
            Button more = new Button().setText(Component.translatable("folkways.selector.more", filing.size() - end));
            Tokens.name(more, token + ".more");
            more.setOnClick(event -> {
                into.removeChild(more);
                layLeaves(into, filing, next);
            });
            into.addChildren(more);
        }
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
            // Every row keeps the icon's place, worn or blank, so the names stand in one column.
            UIElement icon = Rows.icon(leaf.icon());
            icon.addEventListener(UIEvents.MOUSE_DOWN, event -> {
                if (leaf.disabledReason().isEmpty()) {
                    box.setOn(!box.isOn(), true);
                }
            });
            box.addChildAt(icon, 1);
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
            press.addPreIcon(new ItemStackTexture(leaf.icon()));
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
        row.style(style -> style.tooltips(tooltips.apply(leaf)));
        leaf.disabledReason().ifPresent(reason -> row.style(style -> style.tooltips(reason)));
        Tokens.name(row, leaf.token());
        leaves.computeIfAbsent(leaf.key(), key -> new ArrayList<>()).add(row);
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
            box.toggleLabel(label -> label.textStyle(style -> style.textColor(
                !row.isActive() ? 0xFF8F7F62
                    : !on && covered != null && covered.contains(key) ? Rows.QUIET : Rows.INK)));
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
        private final List<IdTree.Folder<Leaf>> children;
        private final UIElement element = column();
        private final UIElement under = column();
        private final Button head = new Button();
        private final Toggle box;
        private boolean open;
        private boolean laid;

        private Shelf(IdTree.Folder<Leaf> folder) {
            this.folder = folder;
            this.children = folder.folders().stream().sorted(ordering).toList();
            this.all = folder.all().stream().map(Leaf::key).distinct().toList();
            shelves.add(this);
            leftward(head);
            head.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).height(ROW));
            head.setOnClick(event -> open(!open));
            String named = folderTokens.apply(folder);
            Tokens.name(head, token + ".folder." + named);
            UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).height(ROW).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2));
            if (ticked && folderSelectable.test(folder.prefix())) {
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
                Tokens.name(box, token + (folderChanged == null ? ".tick." : ".folder_rule.") + named);
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
                layFolders(0);
                layLeaves(under, folder.leaves(), 0);
            }
            under.setDisplay(opened);
            wear();
        }

        private void layFolders(int start) {
            int end = Math.min(start + FOLDERS_AT_ONCE, children.size());
            for (int at = end; at < children.size(); at++) {
                IdTree.Folder<Leaf> inner = children.get(at);
                if (revealing.stream().anyMatch(inner::holds)) {
                    end = at + 1;
                }
            }
            int next = end;
            for (int at = start; at < end; at++) {
                under.addChildren(new Shelf(children.get(at)).element);
            }
            if (end < children.size()) {
                Button more = new Button().setText(Component.translatable("folkways.selector.more",
                    children.size() - end));
                Tokens.name(more, token + ".more");
                more.setOnClick(event -> {
                    under.removeChild(more);
                    layFolders(next);
                });
                under.addChildren(more);
            }
        }

        private void wear() {
            long on = all.stream().filter((covered == null ? marked : covered)::contains).count();
            MutableComponent text = Component.literal(open ? "- " : "+ ").append(naming.apply(folder));
            if (ticked) {
                text.append(Component.literal("  " + on + "/" + all.size()).withColor(Rows.QUIET & 0xFFFFFF));
                if (box != null) {
                    box.setOn(folderMarked == null ? on == all.size() && !all.isEmpty()
                        : folderMarked.test(folder.prefix()), false);
                }
            } else if (on > 0) {
                text.append(Component.literal("  •").withColor(Rows.QUIET & 0xFFFFFF));
            }
            head.setText(text);
        }
    }
    public Picker(String token, boolean many) {
        this(token, many, (word, key) -> {
        });
    }

    public Picker size(float wide, float tall) {
        width = wide;
        height = tall;
        return this;
    }

    public Picker immediate(boolean close) {
        immediate = true;
        closeOnPick = close;
        return this;
    }

    public Picker confirmUnchanged(boolean allowed) {
        confirmUnchanged = allowed;
        return this;
    }

    // What an empty picker says in place of the general "nothing to choose from", such as where its options come from.
    public Picker emptySaying(Component words) {
        empty = words;
        return this;
    }

    public Picker outsideClose(boolean allowed) {
        outsideClose = allowed;
        return this;
    }

    public Picker side(UIElement preview) {
        side = preview;
        return this;
    }

    public void covered(Set<String> keys) {
        covered = Set.copyOf(keys);
        mark(marked);
    }

    public static void rules(UIElement into, String token, List<Row> rows, Consumer<String> remove) {
        rules(into, token, rows, row -> row.label(), remove);
    }

    /** The rules held, newest first; hovering one reads what {@code hover} says of it, such as an item's name. */
    public static void rules(UIElement into, String token, List<Row> rows, Function<Row, Component> hover,
            Consumer<String> remove) {
        into.clearAllChildren();
        if (rows.isEmpty()) {
            into.addChildren(Rows.note(Component.translatable("folkways.selector.rules.empty")));
        }
        for (int at = 0; at < rows.size(); at++) {
            Row rule = rows.get(at);
            Button drop = new Button().setText("×").setOnClick(event -> remove.accept(rule.key()));
            drop.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
            Tokens.name(drop, token + ".rule_drop." + at);
            UIElement row = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
                .widthPercent(100).height(ROW).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2))
                .addChildren(Rows.icon(rule.icon()), Rows.name(rule.label()), drop);
            row.style(style -> style.tooltips(hover.apply(rule)));
            Tokens.name(row, token + ".rule." + at);
            into.addChildren(row);
        }
    }

    public Picker compactFolders(boolean compacted) {
        compact = compacted;
        return this;
    }

    public Picker searchAction(UIElement action) {
        searchAction = action;
        return this;
    }

    public Picker extra(UIElement controls, Consumer<String> searched) {
        extra = controls;
        searching = searched;
        return this;
    }

    public Picker searchFolders(boolean included) {
        searchFolders = included;
        return this;
    }

    public Picker order(Comparator<IdTree.Folder<Leaf>> ordered) {
        ordering = ordered;
        return this;
    }

    public Picker folderSelectable(Predicate<String> selectable) {
        folderSelectable = selectable;
        return this;
    }

    public Picker folderRules(Function<IdTree.Folder<Leaf>, String> tokens,
            BiConsumer<String, Boolean> changed, Predicate<String> marked) {
        folderTokens = tokens;
        folderChanged = changed;
        folderMarked = marked;
        return this;
    }

    public Set<String> marked() {
        return marked;
    }

    public UIElement box() {
        return scroll;
    }

    private final ScrollerView scroll = Rows.fills(Rows.box());

    public Dialog open(UIElement host, Component title, Set<String> current, Consumer<List<String>> taken) {
        held = Set.copyOf(current);
        mark(current);
        confirmed = taken;
        dialog = new Dialog();
        if (extra != null) {
            scroll.addScrollViewChild(extra);
        }
        scroll.addScrollViewChild(tree);
        scroll.addScrollViewChild(results);
        UIElement list = searching(scroll);
        UIElement body = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).heightPercent(100).gapAll(Rows.GAP));
        body.addChildren(list);
        if (side != null) {
            body.addChildren(side);
        }
        dialog.titleBar.addChildren(new Label().setText(title)
            .textStyle(style -> style.textAlignVertical(Vertical.CENTER)
                .textAlignHorizontal(Horizontal.CENTER).adaptiveWidth(true)));
        dialog.addContent(body);
        // A picker that takes each pick at once and stays open has nothing to call off; its button only closes it.
        Button cancel = new Button().setText(Component.translatable(
                immediate && !closeOnPick ? "folkways.action.close" : "folkways.action.cancel"))
            .setOnClick(event -> dialog.close());
        cancel.addClass("__cancel-button__");
        Tokens.name(cancel, token + (token.equals("folkways.choice") ? ".cancel" : ".close"));
        dialog.addButton(cancel);
        if (!immediate) {
            confirm.setOnClick(event -> {
                dialog.close();
                if (confirmUnchanged || !marked.equals(held)) {
                    taken.accept(filled.stream().map(Leaf::key).distinct().filter(marked::contains).toList());
                }
            });
            Tokens.name(confirm, token + ".confirm");
            dialog.addButton(confirm);
        }
        dialog.setClickOutsideClose(outsideClose);
        Tokens.name(dialog, token);
        return show(dialog, host, width, height);
    }

    public record Row(String key, Component label, ItemStack icon, String source, List<String> tags) {
        public Row(String key, Component label, ItemStack icon) {
            this(key, label, icon, sourceOf(key, ""), List.of());
        }
    }

    public static Dialog open(UIElement host, Schema.Setting.Choice choice, ResourceLocation current,
            Consumer<ResourceLocation> confirmed) {
        return open(host, choice.nameKey(), choice.options(), current, confirmed);
    }

    public static Dialog open(UIElement host, String title, List<ResourceLocation> options,
            @Nullable ResourceLocation current, Consumer<ResourceLocation> confirmed) {
        return open(host, Component.translatable(title), options.stream()
            .map(id -> new Row(id.toString(), labelOf(id), iconOf(id))).toList(),
            current == null ? null : current.toString(), key -> confirmed.accept(ResourceLocation.parse(key)));
    }

    public static Dialog open(UIElement host, String title, List<Row> rows, @Nullable String current,
            Consumer<String> confirmed) {
        return open(host, Component.translatable(title), rows, current, confirmed);
    }

    public static Dialog open(UIElement host, Component title, List<Row> rows, @Nullable String current,
            Consumer<String> confirmed) {
        return flat(host, title, rows, current == null ? Set.of() : Set.of(current), false, "folkways.choice",
            picked -> picked.stream().findFirst().ifPresent(confirmed));
    }

    public static Dialog openMany(UIElement host, String title, List<Row> rows, Set<String> current,
            Consumer<List<String>> confirmed) {
        return openMany(host, Component.translatable(title), rows, current, confirmed);
    }

    public static Dialog openMany(UIElement host, Component title, List<Row> rows, Set<String> current,
            Consumer<List<String>> confirmed) {
        return flat(host, title, rows, current, true, "folkways.choice", confirmed);
    }

    public static Dialog openMany(UIElement host, Component title, List<Row> rows, Set<String> current,
            String token, Consumer<List<String>> confirmed) {
        return flat(host, title, rows, current, true, token, confirmed);
    }

    private static Dialog flat(UIElement host, Component title, List<Row> rows, Set<String> current, boolean many,
            String token, Consumer<List<String>> confirmed) {
        Picker picker = new Picker(token, many).outsideClose(token.equals("folkways.choice"))
            .size(token.equals("folkways.choice") ? 180 : 270, 84 + Math.min(Math.max(rows.size(), 1), 8) * 22);
        picker.fill(rows.stream().map(row -> new Leaf(row.key(), "", row.label(),
            token + (token.equals("folkways.choice") ? ".option." : ".offer.")
                + row.key().replace("#", "").replace(':', '.').replace('/', '.'),
            Optional.empty(), row.icon().isEmpty() ? iconOfKey(row.key()) : row.icon(),
            row.source(), row.tags())).toList());
        Tokens.name(picker.scroll, token + ".options");
        return picker.open(host, title, current, confirmed);
    }

    public static void install(IntSupplier depth) {
        aboveWindows = depth;
    }

    public static int depth() {
        return aboveWindows.getAsInt();
    }

    public static Dialog show(Dialog dialog, UIElement host, float wide, float height) {
        UIElement screen = host;
        while (screen.getParent() != null) {
            screen = screen.getParent();
        }
        float width = screen.getSizeWidth();
        float tall = screen.getSizeHeight();
        if (host == screen && (width <= 0 || tall <= 0)) {
            width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            tall = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        }
        tall = Math.max(0, tall - Rows.FOOT);
        float shownWidth = width <= 0 ? wide : Math.min(wide, Math.max(0, width - 16));
        float shownHeight = tall <= 0 ? height : Math.min(height, Math.max(0, tall - 16));
        float x;
        float y;
        UIElement frame = frameOf(host, screen);
        if (frame == null) {
            x = Math.max(0, (width - shownWidth) / 2);
            y = Math.max(0, (tall - shownHeight) / 2);
        } else {
            // The dialog keeps to the window that asked for it, beside the button where there is room inside the
            // frame, so it covers neither a window beside it nor a recipe viewer's list at the screen's edge. A
            // window too small to hold it has it laid over its middle instead.
            float left = frame.getPositionX();
            float top = frame.getPositionY();
            float frameRight = left + frame.getSizeWidth();
            float frameBottom = Math.min(top + frame.getSizeHeight(), tall);
            float after = host.getPositionX() + host.getSizeWidth() + HOST_GAP;
            float before = host.getPositionX() - HOST_GAP - shownWidth;
            if (shownWidth > frameRight - left) {
                x = left + (frame.getSizeWidth() - shownWidth) / 2;
            } else if (after + shownWidth <= frameRight) {
                x = after;
            } else if (before >= left) {
                x = before;
            } else {
                x = Math.max(left, Math.min(host.getPositionX(), frameRight - shownWidth));
            }
            y = shownHeight > frameBottom - top ? top + (frameBottom - top - shownHeight) / 2
                : Math.max(top, Math.min(host.getPositionY(), frameBottom - shownHeight));
            x = Math.max(0, Math.min(x, (width <= 0 ? x + shownWidth : width) - shownWidth));
            y = Math.max(0, Math.min(y, tall - shownHeight));
        }
        dialog.windowMode(x, y, shownWidth, shownHeight);
        dialog.style(style -> style.zIndex(depth()));
        if (host instanceof Button) {
            dialog.addEventListener(UIEvents.REMOVED, event -> {
                UIEvent leave = UIEvent.create(UIEvents.MOUSE_LEAVE);
                leave.target = host;
                UIEventDispatcher.dispatchDirectEvent(leave);
            });
        }
        return dialog.show(host.getModularUI());
    }

    // The window a host stands in: the nearest frame the desk names a window, else the outermost element under the
    // screen. None when the host is the screen itself.
    @Nullable
    private static UIElement frameOf(UIElement host, UIElement screen) {
        if (host == screen) {
            return null;
        }
        UIElement outermost = host;
        for (UIElement at = host; at != null && at != screen; at = at.getParent()) {
            String id = at.getId();
            if (id != null && id.startsWith("folkways.window.") && !id.startsWith("folkways.window.close.")) {
                return at;
            }
            outermost = at;
        }
        return outermost;
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
        String key = "folkways.choice." + option.getNamespace() + "." + option.getPath().replace('/', '.');
        return Component.translatableWithFallback(key, option.getPath());
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
