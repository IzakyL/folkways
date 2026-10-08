package io.github.izakyl.folkways.core.engine.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The vanilla recipes an economy case can run, read from the server's RecipeManager and cut down to the case's
 * goods: a recipe is kept when it makes one of them out of nothing but them, each ingredient matching exactly one.
 * Three questions are asked of it. How much stock a goal could draw at most (every way of making it, sized the
 * least economical way the colony may batch it, fuel included). Whether a goal can be done at all from given stock
 * and stations. And whether what the case ends with can be told from what it began with by recipe runs alone:
 * see {@link #balance}.
 */
final class EconomyBook {

    static final Item FUEL = Items.COAL;
    /** Items one coal cooks. */
    static final int PER_COAL = 8;

    static final List<Block> STATIONS = List.of(Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE,
        Blocks.SMOKER, Blocks.STONECUTTER, Blocks.SMITHING_TABLE);

    record Recipe(Item out, long yield, Map<Item, Long> in, Block station, boolean cooked) {
    }

    /** What the end of a case cannot be explained by: goods with no recipe run to account for them, and goods gone. */
    record Balance(List<String> made, List<String> lost, long cooked, long burned) {
    }

    private final Set<Item> universe;
    private final Map<Item, List<Recipe>> making = new LinkedHashMap<>();
    private final Map<Item, Integer> depth = new HashMap<>();

    private EconomyBook(Set<Item> universe) {
        this.universe = Set.copyOf(universe);
    }

    static EconomyBook of(ServerLevel level, Set<Item> universe) {
        EconomyBook book = new EconomyBook(universe);
        RecipeManager manager = level.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();
        for (RecipeHolder<CraftingRecipe> holder : manager.getAllRecipesFor(RecipeType.CRAFTING)) {
            CraftingRecipe recipe = holder.value();
            if (!recipe.isSpecial() && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)) {
                book.add(recipe.getResultItem(registries), slots(recipe.getIngredients()), Blocks.CRAFTING_TABLE, false);
            }
        }
        book.cooking(manager, registries, RecipeType.SMELTING, Blocks.FURNACE);
        book.cooking(manager, registries, RecipeType.BLASTING, Blocks.BLAST_FURNACE);
        book.cooking(manager, registries, RecipeType.SMOKING, Blocks.SMOKER);
        for (var holder : manager.getAllRecipesFor(RecipeType.STONECUTTING)) {
            book.add(holder.value().getResultItem(registries), slots(holder.value().getIngredients()),
                Blocks.STONECUTTER, false);
        }
        for (RecipeHolder<SmithingRecipe> holder : manager.getAllRecipesFor(RecipeType.SMITHING)) {
            if (holder.value() instanceof SmithingTransformRecipe recipe && !recipe.isIncomplete()) {
                book.add(recipe.getResultItem(registries), List.of(recipe::isTemplateIngredient,
                    recipe::isBaseIngredient, recipe::isAdditionIngredient), Blocks.SMITHING_TABLE, false);
            }
        }
        book.making.values().forEach(ways -> ways.sort(Comparator.comparingLong(Recipe::yield)));
        book.checkRatios();
        return book;
    }

    private static List<Predicate<ItemStack>> slots(List<Ingredient> ingredients) {
        List<Predicate<ItemStack>> out = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (!ingredient.isEmpty()) {
                out.add(ingredient);
            }
        }
        return out;
    }

    private <T extends AbstractCookingRecipe> void cooking(RecipeManager manager, HolderLookup.Provider registries,
                                                          RecipeType<T> type, Block station) {
        for (RecipeHolder<T> holder : manager.getAllRecipesFor(type)) {
            Optional<Ingredient> first = holder.value().getIngredients().stream().filter(one -> !one.isEmpty()).findFirst();
            first.ifPresent(one -> add(holder.value().getResultItem(registries), List.of(one), station, true));
        }
    }

    private void add(ItemStack result, List<Predicate<ItemStack>> slots, Block station, boolean cooked) {
        if (result.isEmpty() || !universe.contains(result.getItem()) || slots.isEmpty()) {
            return;
        }
        Map<Item, Long> in = new LinkedHashMap<>();
        for (Predicate<ItemStack> slot : slots) {
            List<Item> fits = universe.stream().filter(item -> slot.test(new ItemStack(item))).toList();
            if (fits.size() != 1 || fits.getFirst() == result.getItem()) {
                return;
            }
            in.merge(fits.getFirst(), 1L, Long::sum);
        }
        making.computeIfAbsent(result.getItem(), item -> new ArrayList<>())
            .add(new Recipe(result.getItem(), result.getCount(), Map.copyOf(in), station, cooked));
    }

    // Accounting reads every way of making a good as the same trade at a different batch size, so they must be.
    private void checkRatios() {
        making.forEach((out, ways) -> {
            Recipe first = ways.getFirst();
            for (Recipe other : ways) {
                boolean same = other.in().keySet().equals(first.in().keySet()) && other.cooked() == first.cooked()
                    && other.in().entrySet().stream().allMatch(entry ->
                        entry.getValue() * first.yield() == first.in().get(entry.getKey()) * other.yield());
                if (!same) {
                    throw new IllegalStateException("the economy book makes " + name(out) + " two ways at different"
                        + " costs: " + first + " and " + other);
                }
            }
        });
    }

    Set<Item> universe() {
        return universe;
    }

    boolean raw(Item item) {
        return !making.containsKey(item);
    }

    List<Recipe> ways(Item item) {
        return making.getOrDefault(item, List.of());
    }

    /**
     * The most of each good that making {@code count} of {@code item} could draw from stock, whatever way it is
     * made: a batch split across {@code hands} crafters can each round up a run, and a load can take a coal more
     * than its share.
     */
    Map<Item, Long> most(Item item, long count, int hands) {
        Map<Item, Long> out = new LinkedHashMap<>();
        most(item, count, hands, new LinkedHashSet<>(), out);
        return out;
    }

    private void most(Item item, long count, int hands, Set<Item> trail, Map<Item, Long> into) {
        List<Recipe> ways = ways(item);
        if (ways.isEmpty() || trail.contains(item)) {
            into.merge(item, count, Long::sum);
            return;
        }
        trail.add(item);
        Map<Item, Long> worst = new LinkedHashMap<>();
        for (Recipe way : ways) {
            long runs = Math.min(count, up(count, way.yield()) + (way.yield() > 1 ? hands - 1 : 0));
            Map<Item, Long> drawn = new LinkedHashMap<>();
            way.in().forEach((in, each) -> most(in, each * runs, hands, trail, drawn));
            if (way.cooked()) {
                drawn.merge(FUEL, 2 * up(runs, PER_COAL), Long::sum);
            }
            drawn.forEach((good, n) -> worst.merge(good, n, Math::max));
        }
        trail.remove(item);
        worst.forEach((good, n) -> into.merge(good, n, Long::sum));
    }

    /** The most items making {@code count} of {@code item} could put through a cooker, whatever way it is made. */
    long cooked(Item item, long count, int hands) {
        return cooked(item, count, hands, new LinkedHashSet<>());
    }

    private long cooked(Item item, long count, int hands, Set<Item> trail) {
        List<Recipe> ways = ways(item);
        if (ways.isEmpty() || !trail.add(item)) {
            return 0;
        }
        try {
            long worst = 0;
            for (Recipe way : ways) {
                long runs = Math.min(count, up(count, way.yield()) + (way.yield() > 1 ? hands - 1 : 0));
                long here = way.cooked() ? runs : 0;
                for (Map.Entry<Item, Long> in : way.in().entrySet()) {
                    here += cooked(in.getKey(), in.getValue() * runs, hands, trail);
                }
                worst = Math.max(worst, here);
            }
            return worst;
        } finally {
            trail.remove(item);
        }
    }

    /** Whether some way of making it uses only these stations, whatever the stock. */
    boolean makeable(Item item, Set<Block> stations) {
        return makeable(item, stations, new LinkedHashSet<>());
    }

    private boolean makeable(Item item, Set<Block> stations, Set<Item> trail) {
        if (raw(item)) {
            return true;
        }
        if (!trail.add(item)) {
            return false;
        }
        try {
            for (Recipe way : ways(item)) {
                if (stations.contains(way.station())
                        && way.in().keySet().stream().allMatch(in -> makeable(in, stations, trail))) {
                    return true;
                }
            }
            return false;
        } finally {
            trail.remove(item);
        }
    }

    /**
     * Whether {@code count} of {@code item} can be had at all from {@code stock} with these stations, stock first
     * and the rest made; what it takes comes off {@code stock}.
     */
    boolean possible(Item item, long count, Set<Block> stations, Map<Item, Long> stock) {
        return possible(item, count, stations, stock, new LinkedHashSet<>());
    }

    private boolean possible(Item item, long count, Set<Block> stations, Map<Item, Long> stock, Set<Item> trail) {
        long have = Math.min(count, stock.getOrDefault(item, 0L));
        stock.merge(item, -have, Long::sum);
        long left = count - have;
        if (left == 0) {
            return true;
        }
        if (!trail.add(item)) {
            return false;
        }
        try {
            for (Recipe way : ways(item)) {
                if (!stations.contains(way.station())) {
                    continue;
                }
                Map<Item, Long> trial = new HashMap<>(stock);
                long runs = up(left, way.yield());
                boolean fed = way.in().entrySet().stream()
                    .allMatch(in -> possible(in.getKey(), in.getValue() * runs, stations, trial, trail));
                if (fed && way.cooked()) {
                    fed = possible(FUEL, up(runs, PER_COAL), stations, trial, trail);
                }
                if (fed) {
                    stock.clear();
                    stock.putAll(trial);
                    stock.merge(item, runs * way.yield() - left, Long::sum);
                    return true;
                }
            }
            return false;
        } finally {
            trail.remove(item);
        }
    }

    /**
     * The cooker loads one growth of {@code item} could call for at once, by what each cooks: one wherever a
     * cooked good is needed, however deep (a cooked good's own cooked input is a load more, held at the same
     * time), and every way of making counted (the most any way calls for).
     */
    List<Item> loads(Item item) {
        return loads(item, new LinkedHashSet<>());
    }

    private List<Item> loads(Item item, Set<Item> trail) {
        List<Recipe> ways = ways(item);
        if (ways.isEmpty() || !trail.add(item)) {
            return List.of();
        }
        try {
            List<Item> most = List.of();
            for (Recipe way : ways) {
                List<Item> here = new ArrayList<>();
                if (way.cooked()) {
                    here.add(item);
                }
                way.in().keySet().forEach(in -> here.addAll(loads(in, trail)));
                most = here.size() > most.size() ? here : most;
            }
            return most;
        } finally {
            trail.remove(item);
        }
    }

    /** Every good making {@code item} could call for, itself and fuel included. */
    Set<Item> wants(Item item) {
        Set<Item> out = new LinkedHashSet<>();
        wants(item, out);
        return out;
    }

    private void wants(Item item, Set<Item> into) {
        if (!into.add(item)) {
            return;
        }
        for (Recipe way : ways(item)) {
            way.in().keySet().forEach(in -> wants(in, into));
            if (way.cooked()) {
                into.add(FUEL);
            }
        }
    }

    /**
     * What the case ends with, against what it should hold had nothing been made ({@code found - expected}),
     * explained by the fewest recipe runs that could have made it. The most-made goods are unwound first: each
     * good found beyond what is expected is put down to the least runs of its finest-batched recipe at a station
     * the case ever had, which hands their inputs back. Fewest runs is also the cheapest explanation, so what is
     * still over once all is unwound came from nothing, and what is short (a raw good, or a run's yield not all
     * found) went missing. Coal is the exception: burning uses it up, so what is short of it is what burned, and
     * that must be at least one coal in every {@link #PER_COAL} items cooked and at most one per item cooked plus
     * {@code spare} (a coal lit for a load that never finished).
     */
    Balance balance(Map<Item, Long> diff, Set<Block> stationsEver, long spare) {
        Map<Item, Long> left = new LinkedHashMap<>(diff);
        List<Item> order = new ArrayList<>(making.keySet());
        order.sort(Comparator.comparingInt(this::depth).reversed().thenComparing(EconomyBook::name));
        long cooked = 0;
        List<String> lost = new ArrayList<>();
        for (Item out : order) {
            long over = left.getOrDefault(out, 0L);
            Optional<Recipe> finest = ways(out).stream().filter(way -> stationsEver.contains(way.station())).findFirst();
            if (over <= 0 || finest.isEmpty()) {
                continue;
            }
            Recipe way = finest.get();
            long runs = up(over, way.yield());
            left.merge(out, -runs * way.yield(), Long::sum);
            way.in().forEach((in, each) -> left.merge(in, each * runs, Long::sum));
            if (way.cooked()) {
                cooked += runs;
            }
        }
        List<String> made = new ArrayList<>();
        long burned = -left.getOrDefault(FUEL, 0L);
        left.remove(FUEL);
        left.forEach((item, n) -> {
            if (n > 0) {
                made.add(n + " " + name(item));
            } else if (n < 0) {
                lost.add(-n + " " + name(item));
            }
        });
        if (burned < up(cooked, PER_COAL)) {
            made.add((up(cooked, PER_COAL) - burned) + " coal (" + cooked + " items cooked, yet only " + burned
                + " coal is gone)");
        } else if (burned > cooked + spare) {
            lost.add((burned - cooked - spare) + " coal (" + burned + " gone, but only " + cooked
                + " items cooked)");
        }
        return new Balance(made, lost, cooked, burned);
    }

    private int depth(Item item) {
        Integer known = depth.get(item);
        if (known != null) {
            return known;
        }
        depth.put(item, 0);
        int deepest = 0;
        for (Recipe way : ways(item)) {
            for (Item in : way.in().keySet()) {
                deepest = Math.max(deepest, depth(in) + 1);
            }
        }
        depth.put(item, deepest);
        return deepest;
    }

    static long up(long count, long per) {
        return (count + per - 1) / per;
    }

    static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
