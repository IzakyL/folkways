package io.github.izakyl.folkways.core.front.api.ui;

import com.lowdragmc.lowdraglib2.gui.sync.bindings.IDataSource;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.DataBindingBuilder;
import com.lowdragmc.lowdraglib2.gui.sync.bindings.impl.SimpleBinding;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.item.api.ItemFilter;
import io.github.izakyl.folkways.core.item.api.ItemQuery;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

// Items supply their filing and the rules kept by settings; the picker owns their rows and window.
public final class ItemPicker {

    private static final String TOKEN = "folkways.selector";
    private static final int MOST_RULES = 64;
    private final Picker picker;
    private final boolean many;
    private final Set<ItemFilter> picked = new LinkedHashSet<>();
    private final UIElement rules = new UIElement().layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
        .widthPercent(100).gapAll(1));
    private final Button rule = new Button();
    private String query = "";

    private ItemPicker(boolean many, boolean blocks, List<ItemFilter> held) {
        this.many = many;
        picked.addAll(held);
        picker = new Picker(TOKEN, many, (word, key) -> {
            if (!word.equals(Picker.PICK) && !word.equals(Picker.ON) && !word.equals(Picker.OFF)) {
                return;
            }
            if (word.equals(Picker.PICK)) {
                picked.clear();
            }
            ItemFilter filter = ItemFilter.item(ResourceLocation.parse(key));
            if (word.equals(Picker.OFF)) {
                picked.remove(filter);
            } else if (!many || picked.size() < MOST_RULES) {
                picked.add(filter);
            }
            worn();
        });
        picker.compactFolders(false).searchFolders(false).order(Comparator
            .comparingInt((IdTree.Folder<Picker.Leaf> folder) -> folderOrder(folder.prefix()))
            .thenComparing(IdTree.Folder::prefix)).naming(folder -> {
            String prefix = folder.prefix();
            String mod = prefix.substring(0, prefix.indexOf(':'));
            String path = prefix.substring(prefix.indexOf(':') + 1);
            if (path.isEmpty()) {
                return modName(mod);
            }
            String tag = path.substring(0, path.length() - 1);
            return tag.equals("other") ? Component.translatable(TOKEN + ".other")
                : tagName(ResourceLocation.parse(tag.replace('#', ':').replace('~', '/')));
        });
        if (many) {
            picker.folderSelectable(prefix -> folderFilter(prefix).isPresent());
            picker.folderRules(folder -> folderToken(folder.prefix()), (prefix, on) -> {
                folderFilter(prefix).ifPresent(filter -> {
                    if (on) {
                        add(filter);
                    } else {
                        picked.remove(filter);
                        worn();
                    }
                });
            }, prefix -> folderFilter(prefix).map(picked::contains).orElse(false));
            rule.setOnClick(event -> ruleOf(query).ifPresent(this::add));
            Tokens.name(rule, TOKEN + ".add_rule");
            picker.searchAction(rule).extra(rules, typed -> {
                query = typed;
                worn();
            });
        } else {
            picker.folderRules(folder -> folderToken(folder.prefix()), null, null);
        }
        List<Picker.Leaf> options = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || blocks && !(item instanceof BlockItem)) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            List<String> tags = item.builtInRegistryHolder().tags().map(tag -> tag.location().toString()).toList();
            List<String> folders = tags.isEmpty() ? List.of("other")
                : tags.stream().map(tag -> tag.replace(':', '#').replace('/', '~')).toList();
            ItemStack icon = new ItemStack(item);
            for (String folder : folders) {
                String filed = id.getNamespace() + ":" + folder + "/" + id.getPath().replace('/', '~');
                options.add(new Picker.Leaf(id.toString(), filed,
                    item.getDescription(), TOKEN + ".result." + Tokens.of(id), Optional.empty(), icon,
                    id.getNamespace(), tags));
            }
        }
        options.sort(Comparator.comparing(Picker.Leaf::source));
        picker.fill(options);
        ItemDrops.on(picker.box(), new ItemDrops.Drop() {
            @Override
            public boolean accepts(ItemStack stack) {
                return !stack.isEmpty() && (many ? FilterStacks.parse(stack).isPresent()
                    : options.stream().anyMatch(option -> option.key().equals(BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString())));
            }

            @Override
            public void take(ItemStack stack) {
                if (!many) {
                    picked.clear();
                    add(ItemFilter.item(BuiltInRegistries.ITEM.getKey(stack.getItem())));
                } else {
                    FilterStacks.parse(stack).ifPresent(ItemPicker.this::add);
                }
            }
        });
    }

    private static int folderOrder(String prefix) {
        int colon = prefix.indexOf(':');
        String mod = prefix.substring(0, colon);
        String tag = prefix.substring(colon + 1);
        return tag.equals("other/") ? 0 : tag.startsWith(mod + "#") ? 1 : 2;
    }

    private static String folderToken(String prefix) {
        return prefix.substring(0, prefix.length() - 1).replace(':', '.').replace('#', '.')
            .replace('~', '.').replace('/', '.');
    }

    private static Optional<ItemFilter> folderFilter(String prefix) {
        int colon = prefix.indexOf(':');
        String mod = prefix.substring(0, colon);
        String tag = prefix.substring(colon + 1);
        if (tag.isEmpty()) {
            return ItemFilter.query("@" + mod);
        }
        tag = tag.substring(0, tag.length() - 1);
        return tag.equals("other") ? Optional.empty()
            : ItemFilter.query("@" + mod + " #" + tag.replace('#', ':').replace('~', '/'));
    }

    public static Dialog one(UIElement host, Component title, @Nullable ResourceLocation current, boolean blocks,
            Consumer<ResourceLocation> confirmed) {
        ItemPicker items = new ItemPicker(false, blocks,
            current == null ? List.of() : List.of(ItemFilter.item(current)));
        return items.open(host, title, false, chosen -> chosen.stream().findFirst()
            .ifPresent(filter -> confirmed.accept(filter.id())));
    }

    public static Dialog many(UIElement host, Component title, List<ItemFilter> current, boolean blocks,
            Consumer<List<ItemFilter>> confirmed) {
        return many(host, title, current, blocks, false, confirmed);
    }

    public static Dialog many(UIElement host, Component title, List<ItemFilter> current, boolean blocks,
            boolean confirmUnchanged, Consumer<List<ItemFilter>> confirmed) {
        return new ItemPicker(true, blocks, current).open(host, title, confirmUnchanged, confirmed);
    }

    private Dialog open(UIElement host, Component title, boolean confirmUnchanged,
            Consumer<List<ItemFilter>> confirmed) {
        Dialog dialog = picker.confirmUnchanged(confirmUnchanged).open(host, title, keys(),
            ignored -> confirmed.accept(List.copyOf(picked)));
        worn();
        return dialog;
    }

    private Set<String> keys() {
        Set<String> keys = new LinkedHashSet<>();
        picked.forEach(filter -> keys.add(filter.describe()));
        return keys;
    }

    private void add(ItemFilter filter) {
        if (picked.size() < MOST_RULES || !many) {
            picked.add(filter);
        }
        worn();
    }

    private void worn() {
        picker.covered(coveredBy(picked).stream().map(item -> BuiltInRegistries.ITEM.getKey(item).toString())
            .collect(java.util.stream.Collectors.toSet()));
        picker.mark(keys());
        if (!many) {
            return;
        }
        List<ItemFilter> newest = new ArrayList<>(picked);
        Collections.reverse(newest);
        Picker.rules(rules, TOKEN, newest.stream()
            .map(filter -> new Picker.Row(filter.write(), ruleOf(filter), iconOf(filter))).toList(),
            // A rule reads as written; hovering it names what it is, an item by its own name.
            row -> ItemFilter.read(row.key()).map(ItemPicker::labelOf).orElse(row.label()), key -> {
                ItemFilter.read(key).ifPresent(picked::remove);
                worn();
            });
        Optional<ItemFilter> typed = ruleOf(query);
        int count = typed.map(filter -> coveredBy(Set.of(filter)).size()).orElse(0);
        rule.setText(Component.translatable(TOKEN + ".add_rule", count));
        rule.setActive(typed.isPresent() && !picked.contains(typed.get()) && count > 0);
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

    private static Component modName(String namespace) {
        if (namespace.equals(FolkwaysCoreMod.MOD_ID)) {
            return Component.literal("Folkways");
        }
        ModList mods = ModList.get();
        return Component.literal(mods != null && mods.isLoaded(namespace)
            ? mods.getModContainerById(namespace).map(mod -> mod.getModInfo().getDisplayName()).orElse(namespace)
            : namespace);
    }

    private static Component tagName(ResourceLocation tag) {
        return Component.literal("#" + (tag.getNamespace().equals("minecraft") ? tag.getPath() : tag.toString()));
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
