package io.github.izakyl.folkways.front.ui.screen;

import com.lowdragmc.lowdraglib2.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Button;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Dialog;
import com.lowdragmc.lowdraglib2.gui.ui.elements.SearchComponent;
import com.lowdragmc.lowdraglib2.gui.ui.event.UIEvents;
import com.lowdragmc.lowdraglib2.gui.ui.utils.UIElementProvider;
import com.lowdragmc.lowdraglib2.utils.search.IResultHandler;
import dev.vfyjxf.taffy.style.FlexDirection;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.front.api.ui.FilterStacks;
import io.github.izakyl.folkways.front.api.ui.Tokens;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

public final class SelectorDialog {

    private static final float RESULT_ROW = 20f;
    private static final float RESULT_GAP = 2f;
    private static final int ROWS_BEFORE_LAYOUT = 4;

    private static final List<String> HELP_KEYS = List.of(
        "folkways.selector.tooltip",
        "folkways.selector.tooltip.mod",
        "folkways.selector.tooltip.tag",
        "folkways.selector.tooltip.id");

    @Nullable
    private static List<Candidate> pool;

    private SelectorDialog() {
    }

    public record Candidate(ItemStack stack, ResourceLocation id, boolean tag, Component label,
                            String searchName) {

        static Candidate of(ItemStack stack, ResourceLocation id, boolean tag, Component label) {
            return new Candidate(stack, id, tag, label, label.getString().toLowerCase(Locale.ROOT));
        }
    }

    private static final float WINDOW_WIDTH = 260f;
    private static final float WINDOW_HEIGHT = 200f;

    private static final float HOST_GAP = 4f;

    public static Dialog open(UIElement host, Consumer<ItemStack> chosen) {
        return open(host, candidate -> true, chosen);
    }

    // For a setting that names one item: tags are left out, since they name many.
    public static Dialog openItems(UIElement host, Consumer<ItemStack> chosen) {
        return open(host, candidate -> !candidate.tag(), chosen);
    }

    private static Dialog open(UIElement host, Predicate<Candidate> offered, Consumer<ItemStack> chosen) {
        UIElement screen = host;
        while (screen.getParent() != null) {
            screen = screen.getParent();
        }
        float right = host.getPositionX() + host.getSizeWidth() + HOST_GAP;
        float left = host.getPositionX() - HOST_GAP - WINDOW_WIDTH;
        float width = screen.getSizeWidth();
        float height = screen.getSizeHeight();
        // Toward the middle of the screen: EMI keeps the screen's edges, and a dialog laid over its index loses
        // its clicks and keys to it.
        boolean rightHalf = width > 0f && host.getPositionX() + host.getSizeWidth() / 2f > width / 2f;
        float x = width <= 0f || (!rightHalf && right + WINDOW_WIDTH <= width) ? right : Math.max(0f, left);
        float y = height <= 0f
            ? host.getPositionY()
            : Math.max(0f, Math.min(host.getPositionY(), height - WINDOW_HEIGHT));
        return open(host.getModularUI(), x, y, offered, chosen);
    }

    public static Dialog open(@Nullable ModularUI ui, Consumer<ItemStack> chosen) {
        return open(ui, 40f, 40f, chosen);
    }

    public static Dialog open(@Nullable ModularUI ui, float x, float y, Consumer<ItemStack> chosen) {
        return open(ui, x, y, candidate -> true, chosen);
    }

    private static Dialog open(@Nullable ModularUI ui, float x, float y, Predicate<Candidate> offered,
            Consumer<ItemStack> chosen) {
        Dialog dialog = new Dialog();
        int[] fits = { ROWS_BEFORE_LAYOUT };
        UIElementProvider<Candidate> rows = UIElementProvider.iconText(
            candidate -> new ItemStackTexture(candidate.stack()),
            Candidate::label);
        SearchComponent<Candidate> search = new SearchComponent<Candidate>()
            .setCandidateUIProvider(candidate -> named(rows.createUI(candidate), candidate))
            .setSearchUI(new SearchComponent.ISearchUI<>() {
                @Override
                public String resultText(Candidate value) {
                    return value.label().getString();
                }

                @Override
                public void onResultSelected(@Nullable Candidate value) {
                    if (value != null) {
                        chosen.accept(value.stack().copy());
                    }
                    dialog.close();
                }

                @Override
                public void search(String word, IResultHandler<Candidate> found) {
                    Predicate<Candidate> matches = offered.and(matching(word));
                    int room = fits[0];
                    int shown = 0;
                    for (Candidate candidate : pool()) {
                        if (matches.test(candidate)) {
                            if (shown == room) {
                                return;
                            }
                            shown++;
                            found.accept(candidate);
                        }
                    }
                }
            });

        Tokens.name(search, "folkways.selector.search");
        search.textField.style(style -> style.tooltips(help()));
        dialog.setTitle("folkways.selector.title");
        dialog.addContent(new UIElement()
            .layout(layout -> layout.flexDirection(FlexDirection.COLUMN)
                .widthPercent(100).heightPercent(100).gapAll(3))
            .addChildren(search.layout(layout -> layout.widthPercent(100))));
        Button cancel = new Button()
            .setOnClick(event -> dialog.close())
            .setText("folkways.selector.close");
        cancel.addClass("__cancel-button__");
        Tokens.name(cancel, "folkways.selector.close");
        dialog.addButton(cancel);
        dialog.addEventListener(UIEvents.LAYOUT_CHANGED, event -> fitResults(search, cancel, fits));
        dialog.windowMode(x, y, WINDOW_WIDTH, WINDOW_HEIGHT);
        int depth = Window.aboveWindows();
        dialog.style(style -> style.zIndex(depth));
        search.dialog.style(style -> style.zIndex(depth + 1));
        dialog.setClickOutsideClose(false);
        return dialog.show(ui);
    }

    private static void fitResults(SearchComponent<Candidate> search, UIElement floor, int[] fits) {
        float top = search.getPositionY() + search.getSizeHeight();
        float room = floor.getPositionY() - top - RESULT_GAP;
        if (room < RESULT_ROW) {
            return;
        }
        int rows = (int) (room / RESULT_ROW);
        if (fits[0] == rows && search.getSearchStyle().maxItemCount() == rows) {
            return;
        }
        fits[0] = rows;
        search.searchStyle(style -> style.maxItemCount(rows));
    }

    private static UIElement named(UIElement row, Candidate candidate) {
        row.layout(layout -> layout.height(RESULT_ROW).flexShrink(0));
        Tokens.name(row, "folkways.selector.result." + Tokens.of(candidate.id()));
        return row;
    }

    private static Component[] help() {
        return HELP_KEYS.stream().map(Component::translatable).toArray(Component[]::new);
    }

    public static List<Candidate> pool() {
        List<Candidate> known = pool;
        if (known == null) {
            List<Candidate> found = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                if (item == Items.AIR) {
                    continue;
                }
                found.add(Candidate.of(new ItemStack(item), BuiltInRegistries.ITEM.getKey(item), false,
                    item.getDescription()));
            }
            BuiltInRegistries.ITEM.getTagNames().forEach(tagKey -> {
                ResourceLocation id = tagKey.location();
                found.add(Candidate.of(FilterStacks.of(ItemFilter.tag(id)), id, true,
                    Component.literal("#" + id.getPath())));
            });
            found.sort(Comparator.comparing(candidate -> candidate.id().toString()));
            known = List.copyOf(found);
            pool = known;
        }
        return known;
    }

    public static Predicate<Candidate> matching(String query) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            return candidate -> true;
        }
        List<Predicate<Candidate>> alternatives = new ArrayList<>();
        for (String part : trimmed.split("\\|")) {
            alternatives.add(all(part));
        }
        return alternatives.stream().reduce(candidate -> false, Predicate::or);
    }

    private static Predicate<Candidate> all(String part) {
        List<Predicate<Candidate>> terms = new ArrayList<>();
        for (String term : part.toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (!term.isEmpty()) {
                terms.add(term(term));
            }
        }
        return terms.isEmpty()
            ? candidate -> true
            : terms.stream().reduce(candidate -> true, Predicate::and);
    }

    private static Predicate<Candidate> term(String term) {
        if (term.length() > 1) {
            String needle = term.substring(1);
            switch (term.charAt(0)) {
                case '@':
                    return candidate -> candidate.id().getNamespace().contains(needle);
                case '#':
                    return candidate -> candidate.tag()
                        ? candidate.id().toString().contains(needle)
                        : candidate.stack().getTags()
                            .anyMatch(tag -> tag.location().toString().contains(needle));
                case '*':
                    return candidate -> candidate.id().toString().contains(needle);
                default:
                    break;
            }
        }
        return candidate -> candidate.searchName().contains(term)
            || candidate.id().getPath().contains(term);
    }
}
