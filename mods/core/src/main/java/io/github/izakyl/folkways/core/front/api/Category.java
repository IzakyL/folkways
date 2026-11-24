package io.github.izakyl.folkways.core.front.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.resources.ResourceLocation;

/**
 * What part of the colony's life something the book draws serves: its food, its homes, moving its goods, its defence,
 * its building. The view from above groups its drawing tools by these, and the book's picker lists a drawn shape's
 * candidates under them. A {@link Delegation}, an offer or a shelf names its category by id; one that names none, or
 * one never registered, falls under {@link #OTHER}. Modules may register categories of their own during setup.
 */
public record Category(ResourceLocation id, String nameKey, ResourceLocation icon, int order) {

    /** Taking blocks, animals and residents into the colony: the book's pointing, and what takes a box in at once. */
    public static final Category TAKING = new Category(ResourceLocation.fromNamespaceAndPath("folkways_core", "taking"),
        "folkways.category.taking", ResourceLocation.fromNamespaceAndPath("folkways_core", "colony_book"), 0);
    public static final Category FOOD = core("food", "wheat", 10);
    public static final Category LIVING = core("living", "red_bed", 20);
    public static final Category LOGISTICS = core("logistics", "chest_minecart", 30);
    public static final Category DEFENSE = core("defense", "shield", 40);
    public static final Category CONSTRUCTION = core("construction", "scaffolding", 50);
    public static final Category OTHER = core("other", "paper", 1000);

    private static final Map<ResourceLocation, Category> KNOWN = new ConcurrentHashMap<>();

    static {
        for (Category known : List.of(TAKING, FOOD, LIVING, LOGISTICS, DEFENSE, CONSTRUCTION, OTHER)) {
            KNOWN.put(known.id(), known);
        }
    }

    public Category {
        Objects.requireNonNull(id);
        Objects.requireNonNull(nameKey);
        Objects.requireNonNull(icon);
    }

    /** Adds a category; its place among the others is its order, lowest first. */
    public static void register(Category category) {
        if (KNOWN.putIfAbsent(category.id(), category) != null) {
            throw new IllegalArgumentException("book category already registered: " + category.id());
        }
    }

    /** The category of that id, or {@link #OTHER} when there is none. */
    public static Category of(ResourceLocation id) {
        return Optional.ofNullable(id).map(KNOWN::get).orElse(OTHER);
    }

    public static final Comparator<Category> ORDER = Comparator.comparingInt(Category::order)
        .thenComparing(category -> category.id().toString());

    private static Category core(String path, String icon, int order) {
        return new Category(ResourceLocation.fromNamespaceAndPath("folkways_core", path), "folkways.category." + path,
            ResourceLocation.withDefaultNamespace(icon), order);
    }
}
