package io.github.izakyl.folkways.front.api.ui;

import io.github.izakyl.folkways.FolkwaysMod;
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
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import dev.vfyjxf.taffy.style.AlignContent;
import dev.vfyjxf.taffy.style.AlignItems;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.ItemQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

// The one way an item is chosen: every item there is filed in a folder for each mod and, in it, one for each tag,
// with a search over them all. It picks one item, or holds a list of rules: items, folders, and searches kept as
// they were typed, each a rule of its own. A picker of what something offers holds those offers alone.
public final class ItemPicker {

    private static final String CHOSEN = "folkways_chosen";
    private static final String TOKEN = "folkways.selector";

    private static final float WIDTH = 270f;
    private static final float HEIGHT = 340f;
    private static final float ROW = 18f;
    private static final float INDENT = 10f;
    private static final float BOX = 16f;
    private static final float SCREEN_MARGIN = 8f;
    private static final float HOST_GAP = 4f;

    // How many items a folder or a search lays out before it asks to show more.
    private static final int LAID_AT_ONCE = 120;
    // A mod's tag folders are many lines each; Minecraft alone carries hundreds of tags.
    private static final int FOLDERS_AT_ONCE = 40;
    private static final int MOST_RULES = 64;
    // A search that finds no more than this opens its tag folders too; one that finds more opens only its mods.
    private static final int UNFOLDED_AT_MOST = 40;

    private static final List<String> HELP_KEYS = List.of(
        TOKEN + ".tooltip",
        TOKEN + ".tooltip.mod",
        TOKEN + ".tooltip.tag",
        TOKEN + ".tooltip.id",
        TOKEN + ".tooltip.whole");

    /** One thing a picker of offers holds out: the rule ticked, the words it reads as, the icon it wears. */
    public record Offer(ItemFilter filter, Component label, ItemStack icon) {
    }

    private enum Mode {
        ONE,
        MANY,
        AMONG
    }

    private final Mode mode;
    private final List<Item> pool;
    private final List<Offer> offers;
    private final Map<Item, String> names = new HashMap<>();
    private final Set<ItemFilter> held;
    private final Set<ItemFilter> picked = new LinkedHashSet<>();
    private Set<Item> covered = Set.of();

    private final Map<Item, List<UIElement>> leaves = new HashMap<>();
    private final List<Shelf> shelves = new ArrayList<>();
    private final Map<ItemFilter, Toggle> offered = new HashMap<>();

    private final UIElement tree = column();
    private final UIElement results = column();
    // One box scrolls them all: the rules held on top, and under them the folders, or what a search finds.
    private final ScrollerView box = Rows.fills(Rows.box());
    private final UIElement rules = column();
    private final Button rule = new Button();
    private final Button confirm = new Button().setText(Component.translatable("folkways.action.confirm"));
    private String query = "";

    private ItemPicker(Mode mode, List<Item> pool, List<Offer> offers, Set<ItemFilter> held) {
        this.mode = mode;
        this.pool = pool;
        this.offers = offers;
        this.held = Set.copyOf(held);
        this.picked.addAll(held);
        for (Item item : pool) {
            names.put(item, item.getDescription().getString().toLowerCase(Locale.ROOT));
        }
    }

    // One item, from every item there is, or every block.
    public static Dialog one(UIElement host, Component title, @Nullable ResourceLocation current, boolean blocks,
            Consumer<ResourceLocation> confirmed) {
        Set<ItemFilter> held = current == null ? Set.of() : Set.of(ItemFilter.item(current));
        ItemPicker picker = new ItemPicker(Mode.ONE, poolOf(blocks), List.of(), held);
        return picker.show(host, title, picked -> picked.stream().findFirst().ifPresent(one -> confirmed.accept(one.id())));
    }

    // A list of rules, any items, tags and searches.
    public static Dialog many(UIElement host, Component title, List<ItemFilter> current, boolean blocks,
            Consumer<List<ItemFilter>> confirmed) {
        ItemPicker picker = new ItemPicker(Mode.MANY, poolOf(blocks), List.of(), new LinkedHashSet<>(current));
        return picker.show(host, title, confirmed);
    }

    // Any of what is offered, and nothing else.
    public static Dialog among(UIElement host, Component title, List<Offer> offers, List<ItemFilter> current,
            Consumer<List<ItemFilter>> confirmed) {
        Set<ItemFilter> held = new LinkedHashSet<>();
        for (Offer offer : offers) {
            if (current.contains(offer.filter())) {
                held.add(offer.filter());
            }
        }
        ItemPicker picker = new ItemPicker(Mode.AMONG, List.of(), List.copyOf(offers), held);
        return picker.show(host, title, confirmed);
    }

    private static List<Item> poolOf(boolean blocks) {
        List<Item> pool = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item != Items.AIR && (!blocks || item instanceof BlockItem)) {
                pool.add(item);
            }
        }
        return List.copyOf(pool);
    }

    private Dialog show(UIElement host, Component title, Consumer<List<ItemFilter>> confirmed) {
        Dialog dialog = new Dialog();
        TextField search = new TextField();
        search.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0));
        search.style(style -> style.tooltips(help()));
        search.textFieldStyle(style -> style.placeholder(Component.translatable(TOKEN + ".search")));
        search.setTextResponder(typed -> {
            query = typed == null ? "" : typed.trim();
            searched();
        });
        Tokens.name(search, TOKEN + ".search");
        UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(Rows.GAP));
        line.addChildren(search);
        if (mode == Mode.MANY) {
            rule.setOnClick(event -> ruleOf(query).ifPresent(this::add));
            rule.layout(layout -> layout.flexShrink(0));
            Tokens.name(rule, TOKEN + ".add_rule");
            line.addChildren(rule);
        }

        results.setDisplay(false);
        Tokens.name(tree, TOKEN + ".tree");
        Tokens.name(results, TOKEN + ".results");
        UIElement listed = column();
        if (mode == Mode.MANY) {
            Tokens.name(rules, TOKEN + ".rules");
            listed.addChildren(Rows.note(Component.translatable(TOKEN + ".rules")), rules,
                Rows.note(Component.translatable(TOKEN + ".items")));
        }
        listed.addChildren(tree, results);
        box.addScrollViewChild(listed);
        UIElement body = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
            .widthPercent(100).heightPercent(100).gapAll(3));
        body.addChildren(line, box);
        if (mode == Mode.AMONG) {
            for (Offer offer : offers) {
                tree.addChildren(offerRow(offer));
            }
        } else {
            lay();
        }
        // An item dragged in from a recipe viewer is one more rule, or the one item picked.
        ItemDrops.on(box, new ItemDrops.Drop() {
            @Override
            public boolean accepts(ItemStack stack) {
                return switch (mode) {
                    case MANY -> !stack.isEmpty() && FilterStacks.parse(stack).isPresent();
                    case ONE -> !stack.isEmpty() && pool.contains(stack.getItem());
                    case AMONG -> false;
                };
            }

            @Override
            public void take(ItemStack stack) {
                if (mode == Mode.ONE) {
                    pick(stack.getItem());
                } else {
                    FilterStacks.parse(stack).ifPresent(ItemPicker.this::add);
                }
            }
        });

        confirm.setOnClick(event -> {
            dialog.close();
            if (!picked.equals(held)) {
                confirmed.accept(inOrder());
            }
        });
        Tokens.name(confirm, TOKEN + ".confirm");
        Button cancel = new Button()
            .setText(Component.translatable("folkways.action.cancel"))
            .setOnClick(event -> dialog.close());
        cancel.addClass("__cancel-button__");
        Tokens.name(cancel, TOKEN + ".close");

        dialog.titleBar.addChildren(new Label().setText(title)
            .textStyle(style -> style.textAlignVertical(Vertical.CENTER)
                .textAlignHorizontal(Horizontal.CENTER).adaptiveWidth(true)));
        dialog.addContent(body);
        dialog.addButton(cancel);
        dialog.addButton(confirm);
        dialog.setClickOutsideClose(false);
        Tokens.name(dialog, TOKEN);
        worn();
        return place(dialog, host);
    }

    private List<ItemFilter> inOrder() {
        if (mode != Mode.AMONG) {
            return List.copyOf(picked);
        }
        List<ItemFilter> ordered = new ArrayList<>();
        for (Offer offer : offers) {
            if (picked.contains(offer.filter())) {
                ordered.add(offer.filter());
            }
        }
        return ordered;
    }

    // Beside what asked for it, over every window open; on a screen of its own, in the middle of it.
    private static Dialog place(Dialog dialog, UIElement host) {
        UIElement screen = host;
        while (screen.getParent() != null) {
            screen = screen.getParent();
        }
        float width = screen.getSizeWidth();
        float tall = screen.getSizeHeight();
        if (host == screen && (width <= 0f || tall <= 0f)) {
            // A screen opened this tick is not laid out yet; the window's size is what it will be.
            width = Minecraft.getInstance().getWindow().getGuiScaledWidth();
            tall = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        }
        tall = Math.max(0f, tall - Rows.FOOT);
        float height = tall <= 0f ? HEIGHT : Math.min(HEIGHT, tall - 2 * SCREEN_MARGIN);
        float x;
        float y;
        if (host == screen) {
            x = Math.max(0f, (width - WIDTH) / 2f);
            y = Math.max(0f, (tall - height) / 2f);
        } else {
            float right = host.getPositionX() + host.getSizeWidth() + HOST_GAP;
            float left = host.getPositionX() - HOST_GAP - WIDTH;
            x = width <= 0f || right + WIDTH <= width ? right : Math.max(0f, left);
            y = tall <= 0f ? host.getPositionY() : Math.max(0f, Math.min(host.getPositionY(), tall - height));
        }
        dialog.windowMode(x, y, WIDTH, height);
        int depth = ChoicePicker.depth();
        dialog.style(style -> style.zIndex(depth));
        return dialog.show(host.getModularUI());
    }

    // The mods there are, each a folder of its tags and of what carries none.
    private void lay() {
        Map<String, List<Item>> byMod = byMod(pool);
        byMod.forEach((mod, items) -> {
            Shelf shelf = new Shelf(mod, modName(mod), filterOf("@" + mod), items, 0, false, false);
            tree.addChildren(shelf.element);
            if (byMod.size() == 1) {
                shelf.open(true);
            }
        });
    }

    // What a search finds, filed as the whole tree is: a folder holds only what was found in it, so a folder that
    // found nothing is not there at all. Its mods stand open, and a short find opens its tags as well.
    private void layFound(List<Item> found) {
        boolean unfolded = found.size() <= UNFOLDED_AT_MOST;
        byMod(found).forEach((mod, items) -> {
            Shelf shelf = new Shelf(mod, modName(mod), filterOf("@" + mod), items, 0, true, unfolded);
            results.addChildren(shelf.element);
            shelf.open(true);
        });
    }

    private static Map<String, List<Item>> byMod(List<Item> items) {
        Map<String, List<Item>> byMod = new TreeMap<>();
        for (Item item : items) {
            byMod.computeIfAbsent(BuiltInRegistries.ITEM.getKey(item).getNamespace(), any -> new ArrayList<>())
                .add(item);
        }
        return byMod;
    }

    private void searched() {
        boolean searching = !query.isEmpty();
        tree.setDisplay(!searching || mode == Mode.AMONG);
        results.setDisplay(searching && mode != Mode.AMONG);
        if (mode == Mode.AMONG) {
            Optional<ItemQuery> asked = ItemQuery.parse(query);
            for (Offer offer : offers) {
                Toggle box = offered.get(offer.filter());
                if (box != null && box.getParent() != null) {
                    box.getParent().setDisplay(asked.isEmpty() || offers(asked.get(), offer));
                }
            }
        } else {
            results.clearAllChildren();
            shelves.removeIf(shelf -> shelf.found);
            leaves.values().forEach(laid -> laid.removeIf(this::foundLeaf));
            if (searching) {
                ItemQuery.parse(query).ifPresent(asked -> {
                    List<Item> found = new ArrayList<>();
                    for (Item item : pool) {
                        if (asked.matches(item, names.get(item))) {
                            found.add(item);
                        }
                    }
                    layFound(found);
                    if (found.isEmpty()) {
                        results.addChildren(Rows.note(Component.translatable(TOKEN + ".none")));
                    }
                });
            }
        }
        worn();
    }

    // A leaf laid under the last search, or one no longer laid anywhere.
    private boolean foundLeaf(UIElement leaf) {
        for (UIElement above = leaf.getParent(); above != null; above = above.getParent()) {
            if (above == results) {
                return true;
            }
        }
        return leaf.getParent() == null;
    }

    private static boolean offers(ItemQuery asked, Offer offer) {
        String label = offer.label().getString().toLowerCase(Locale.ROOT);
        if (offer.filter().kind() == ItemFilter.Kind.ITEM) {
            Item item = BuiltInRegistries.ITEM.get(offer.filter().id());
            return asked.matches(item, label);
        }
        return label.contains(asked.text().toLowerCase(Locale.ROOT))
            || offer.filter().describe().contains(asked.text().toLowerCase(Locale.ROOT));
    }

    // Lays out the first so many items, and a line that lays out more.
    private void layItems(UIElement into, List<Item> items, int most, Consumer<Button> more) {
        for (int at = 0; at < Math.min(most, items.size()); at++) {
            into.addChildren(leaf(items.get(at)));
        }
        if (items.size() > most) {
            Button rest = new Button().setText(Component.translatable(TOKEN + ".more", items.size() - most));
            leftward(rest);
            rest.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
            rest.setOnClick(event -> more.accept(rest));
            Tokens.name(rest, TOKEN + ".more");
            into.addChildren(rest);
        }
    }

    private UIElement leaf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        Component name = item.getDescription();
        UIElement leaf;
        if (mode == Mode.ONE) {
            Button press = new Button().setText(name);
            press.addPreIcon(new ItemStackTexture(new ItemStack(item)));
            leftward(press);
            press.setOnClick(event -> pick(item));
            leaf = press;
        } else {
            Toggle box = new Toggle().noText();
            box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
            box.setOnToggleChanged(on -> {
                ItemFilter one = ItemFilter.item(id);
                if (on) {
                    add(one);
                } else {
                    remove(one);
                }
            });
            leaf = line(box, Rows.icon(new ItemStack(item)), Rows.name(name));
            // The name and icon beside the box answer a click as the box does.
            for (UIElement beside : leaf.getChildren()) {
                if (beside != box) {
                    beside.addEventListener(UIEvents.MOUSE_DOWN, event -> box.setOn(!box.isOn(), true));
                }
            }
        }
        leaf.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
        leaf.style(style -> style.tooltips(Component.literal(id.toString())));
        Tokens.name(leaf, TOKEN + ".result." + Tokens.of(id));
        leaves.computeIfAbsent(item, any -> new ArrayList<>()).add(leaf);
        wear(item, leaf);
        return leaf;
    }

    private UIElement offerRow(Offer offer) {
        Toggle box = new Toggle().noText();
        box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
        box.setOnToggleChanged(on -> {
            if (on) {
                picked.add(offer.filter());
            } else {
                picked.remove(offer.filter());
            }
            worn();
        });
        offered.put(offer.filter(), box);
        UIElement row = line(box, Rows.icon(offer.icon()), Rows.name(offer.label()));
        for (UIElement beside : row.getChildren()) {
            if (beside != box) {
                beside.addEventListener(UIEvents.MOUSE_DOWN, event -> box.setOn(!box.isOn(), true));
            }
        }
        Tokens.name(row, TOKEN + ".offer." + tokenOf(offer.filter().describe()));
        return row;
    }

    private void pick(Item item) {
        picked.clear();
        picked.add(ItemFilter.item(BuiltInRegistries.ITEM.getKey(item)));
        worn();
    }

    private void add(ItemFilter filter) {
        if (picked.size() < MOST_RULES && picked.add(filter)) {
            worn();
        }
    }

    private void remove(ItemFilter filter) {
        if (picked.remove(filter)) {
            worn();
        }
    }

    // Everything on show is dressed again for what is picked now.
    private void worn() {
        covered = coveredBy(picked);
        leaves.forEach((item, laid) -> laid.forEach(leaf -> wear(item, leaf)));
        shelves.forEach(Shelf::wear);
        offered.forEach((filter, box) -> box.setOn(picked.contains(filter), false));
        if (mode == Mode.MANY) {
            wearRules();
            Optional<ItemFilter> typed = ruleOf(query);
            rule.setText(Component.translatable(TOKEN + ".add_rule",
                typed.map(filter -> coveredBy(Set.of(filter)).size()).orElse(0)));
            rule.setActive(typed.isPresent() && !picked.contains(typed.get())
                && !coveredBy(Set.of(typed.get())).isEmpty());
        }
        confirm.setActive(!picked.equals(held) && (mode != Mode.ONE || !picked.isEmpty()));
    }

    private void wear(Item item, UIElement leaf) {
        ItemFilter one = ItemFilter.item(BuiltInRegistries.ITEM.getKey(item));
        boolean own = picked.contains(one);
        if (leaf instanceof Button press) {
            if (own) {
                press.addClass(CHOSEN);
            } else {
                press.removeClass(CHOSEN);
            }
            return;
        }
        for (UIElement part : leaf.getChildren()) {
            if (part instanceof Toggle box) {
                box.setOn(own, false);
            } else if (part instanceof Label name) {
                // Taken in by another rule, though not ticked of its own.
                name.textStyle(style -> style.textColor(!own && covered.contains(item) ? Rows.QUIET : Rows.INK));
            }
        }
    }

    private void wearRules() {
        rules.clearAllChildren();
        if (picked.isEmpty()) {
            rules.addChildren(Rows.note(Component.translatable(TOKEN + ".rules.empty")));
            return;
        }
        // The newest first, so a rule just added is the one at hand.
        List<ItemFilter> newestFirst = new ArrayList<>(picked);
        Collections.reverse(newestFirst);
        int at = 0;
        for (ItemFilter filter : newestFirst) {
            Button drop = new Button().setText("×");
            drop.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
            drop.style(style -> style.tooltips(Component.translatable(TOKEN + ".remove")));
            drop.setOnClick(event -> remove(filter));
            Tokens.name(drop, TOKEN + ".rule_drop." + at);
            UIElement row = line(Rows.icon(iconOf(filter)), Rows.name(ruleOf(filter)), drop);
            row.style(style -> style.tooltips(labelOf(filter)));
            Tokens.name(row, TOKEN + ".rule." + at);
            rules.addChildren(row);
            at++;
        }
    }

    // What a search is kept as: the query it is, an item or a tag when it names just one.
    private static Optional<ItemFilter> ruleOf(String typed) {
        return ItemFilter.query(typed);
    }

    // A rule as it is written, a query like any other, with what it takes in when that is more than one.
    private static Component ruleOf(ItemFilter filter) {
        if (filter.kind() == ItemFilter.Kind.ITEM) {
            return Component.literal(filter.describe());
        }
        return Component.translatable(TOKEN + ".query", filter.describe(), coveredBy(Set.of(filter)).size());
    }

    private static ItemFilter filterOf(String query) {
        return ItemFilter.query(query).orElseThrow();
    }

    // Every item a rule takes in, as the server will read it.
    private static Set<Item> coveredBy(Set<ItemFilter> rules) {
        Set<Item> found = new HashSet<>();
        for (ItemFilter filter : rules) {
            switch (filter.kind()) {
                case ITEM -> BuiltInRegistries.ITEM.getOptional(filter.id()).ifPresent(found::add);
                case TAG -> BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, filter.id()))
                    .ifPresent(members -> members.forEach(member -> found.add(member.value())));
                case QUERY -> ItemQuery.parse(filter.text()).ifPresent(query -> found.addAll(query.items()));
            }
        }
        found.remove(Items.AIR);
        return found;
    }

    // How a rule reads: an item by its name, a tag by its id, a search as it was typed and what it finds.
    public static Component labelOf(ItemFilter filter) {
        return switch (filter.kind()) {
            case ITEM -> BuiltInRegistries.ITEM.getOptional(filter.id()).filter(item -> item != Items.AIR)
                .<Component>map(Item::getDescription)
                .orElse(Component.literal(filter.text()));
            case TAG -> Component.literal(filter.describe());
            case QUERY -> Component.translatable(TOKEN + ".query", filter.text(),
                ItemQuery.parse(filter.text()).map(query -> query.items().size()).orElse(0));
        };
    }

    public static ItemStack iconOf(ItemFilter filter) {
        return FilterStacks.of(filter);
    }

    // A list of rules as lines of text, to travel to the server and back.
    public static String encode(List<ItemFilter> filters) {
        return String.join("\n", filters.stream().map(ItemFilter::write).toList());
    }

    public static List<ItemFilter> decode(String lines) {
        List<ItemFilter> filters = new ArrayList<>();
        for (String line : lines.split("\n")) {
            ItemFilter.read(line).ifPresent(filters::add);
        }
        return filters;
    }

    private static UIElement column() {
        return new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN).widthPercent(100).gapAll(1));
    }

    private static UIElement line(UIElement... parts) {
        UIElement line = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.ROW)
            .widthPercent(100).height(ROW).flexShrink(0).alignItems(AlignItems.CENTER).gapAll(2));
        line.addChildren(parts);
        return line;
    }

    // A button reads from its left edge, as a line in a list does.
    private static void leftward(Button press) {
        press.layout(layout -> layout.justifyContent(AlignContent.FLEX_START).paddingHorizontal(4));
        press.textStyle(style -> style.textAlignHorizontal(Horizontal.LEFT).textWrap(TextWrap.HIDE));
    }

    private static Component[] help() {
        return HELP_KEYS.stream().map(Component::translatable).toArray(Component[]::new);
    }

    private static String tokenOf(String key) {
        return key.replaceAll("[^a-z0-9_.]", ".");
    }

    private static Component modName(String namespace) {
        // Every Folkways mod registers under one namespace that none of them has for an id.
        if (namespace.equals(FolkwaysMod.MOD_ID)) {
            return Component.literal("Folkways");
        }
        ModList mods = ModList.get();
        String named = mods != null && mods.isLoaded(namespace)
            ? mods.getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace)
            : namespace;
        return Component.literal(named);
    }

    private static Component tagName(ResourceLocation tag) {
        return Component.literal("#" + (tag.getNamespace().equals("minecraft") ? tag.getPath() : tag.toString()));
    }

    // One folder: a mod, a tag in it, or what in it carries none. A folder that is a rule has a box to tick it by.
    private final class Shelf {
        private final String key;
        private final Component name;
        @Nullable
        private final ItemFilter folder;
        private final List<Item> items;
        private final int depth;
        // Laid under a search, not in the whole tree; it opens its own folders when it opens if it is unfolded.
        private final boolean found;
        private final boolean unfolded;
        private final UIElement element = column();
        private final UIElement under = column();
        private final Button head = new Button();
        @Nullable
        private final Toggle box;
        private boolean open;
        private boolean laid;
        private int most = LAID_AT_ONCE;

        private Shelf(String key, Component name, @Nullable ItemFilter folder, List<Item> items, int depth,
                boolean found, boolean unfolded) {
            this.key = key;
            this.name = name;
            this.folder = folder;
            this.items = items;
            this.depth = depth;
            this.found = found;
            this.unfolded = unfolded;
            shelves.add(this);
            leftward(head);
            head.layout(layout -> layout.flexGrow(1).flexShrink(1).minWidth(0).height(ROW));
            head.setOnClick(event -> open(!open));
            String named = tokenOf(key);
            Tokens.name(head, TOKEN + ".folder." + named);
            UIElement line = line();
            if (mode == Mode.MANY && folder != null) {
                box = new Toggle().noText();
                box.layout(layout -> layout.width(BOX).height(BOX).flexShrink(0));
                box.setOnToggleChanged(on -> {
                    if (on) {
                        add(folder);
                    } else {
                        remove(folder);
                    }
                });
                box.style(style -> style.tooltips(Component.translatable(TOKEN + ".folder.toggle",
                    folder.describe())));
                Tokens.name(box, TOKEN + ".folder_rule." + named);
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
                if (depth == 0) {
                    most = FOLDERS_AT_ONCE;
                    layTags();
                } else {
                    layLeaves();
                }
            }
            under.setDisplay(opened);
            wear();
        }

        // A mod's folder holds one for each tag its items carry, and one for those that carry none, so many at once.
        private void layTags() {
            under.clearAllChildren();
            // The mod's own tags first, then those it shares under another's name.
            Map<ResourceLocation, List<Item>> byTag = new TreeMap<>(Comparator
                .comparing((ResourceLocation tag) -> !tag.getNamespace().equals(key))
                .thenComparing(ResourceLocation::toString));
            List<Item> untagged = new ArrayList<>();
            for (Item item : items) {
                Holder.Reference<Item> holder = item.builtInRegistryHolder();
                List<TagKey<Item>> tags = holder.tags().toList();
                if (tags.isEmpty()) {
                    untagged.add(item);
                }
                for (TagKey<Item> tag : tags) {
                    byTag.computeIfAbsent(tag.location(), any -> new ArrayList<>()).add(item);
                }
            }
            List<Shelf> laidHere = new ArrayList<>();
            if (!untagged.isEmpty()) {
                laidHere.add(new Shelf(key + "#", Component.translatable(TOKEN + ".other"), null,
                    untagged, 1, found, false));
            }
            int laid = 0;
            Button rest = null;
            for (Map.Entry<ResourceLocation, List<Item>> tag : byTag.entrySet()) {
                if (laid++ == most) {
                    rest = new Button().setText(Component.translatable(TOKEN + ".more", byTag.size() - most));
                    leftward(rest);
                    rest.layout(layout -> layout.widthPercent(100).height(ROW).flexShrink(0));
                    rest.setOnClick(event -> {
                        most += FOLDERS_AT_ONCE;
                        shelves.removeIf(shelf -> shelf != this && shelf.found == found
                            && shelf.key.startsWith(key + "#"));
                        layTags();
                        worn();
                    });
                    Tokens.name(rest, TOKEN + ".more");
                    break;
                }
                laidHere.add(new Shelf(key + "#" + tag.getKey(), tagName(tag.getKey()),
                    filterOf("@" + key + " #" + tag.getKey()), tag.getValue(), 1, found, false));
            }
            for (Shelf shelf : laidHere) {
                under.addChildren(shelf.element);
                if (unfolded) {
                    shelf.open(true);
                }
            }
            if (rest != null) {
                under.addChildren(rest);
            }
        }

        private void layLeaves() {
            under.clearAllChildren();
            leaves.values().forEach(laid -> laid.removeIf(leaf -> leaf.getParent() == null));
            layItems(under, items, most, rest -> {
                most += LAID_AT_ONCE;
                layLeaves();
                worn();
            });
        }

        private void wear() {
            long on = items.stream().filter(covered::contains).count();
            MutableComponent text = Component.literal(open ? "- " : "+ ").append(name);
            text.append(Component.literal("  " + on + "/" + items.size()).withColor(Rows.QUIET & 0xFFFFFF));
            head.setText(text);
            if (box != null) {
                box.setOn(folder != null && picked.contains(folder), false);
            }
        }
    }

    // What the client picked, told to the server: the setting's key and its rules, numbered so a second send
    // inside one tick is not lost to the first.
    public static final class Sending {

        private int sequence;
        private String sent = "";

        public Sending(UIElement on, BiConsumer<String, List<ItemFilter>> received) {
            SimpleBinding<String> binding = DataBindingBuilder.stringC2S(sealed -> {
                String[] parts = sealed.split("\n", 3);
                if (parts.length >= 2 && !parts[1].isEmpty()) {
                    received.accept(parts[1], parts.length == 3 ? decode(parts[2]) : List.of());
                }
            }).build();
            binding.setRemoteDataSource(IDataSource.of(ignored -> {
            }, () -> sent));
            on.addSyncValue(binding.getSyncValue());
        }

        public void send(String key, List<ItemFilter> filters) {
            sent = ++sequence + "\n" + key + (filters.isEmpty() ? "" : "\n" + encode(filters));
        }
    }
}
