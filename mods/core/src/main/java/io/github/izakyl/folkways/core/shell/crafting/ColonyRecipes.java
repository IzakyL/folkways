package io.github.izakyl.folkways.core.shell.crafting;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import io.github.izakyl.folkways.core.plan.internal.Refinements;
import io.github.izakyl.folkways.core.work.api.Amount;
import io.github.izakyl.folkways.core.work.api.Need;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

/** The material repertoire is shared by both optional network providers. */
public final class ColonyRecipes {

    public record Entry(String reference, Workshop workshop, WorkshopRecipes.Recipe recipe) {
        public Optional<Grown> work() {
            return work(1);
        }

        public Optional<Grown> work(long runs) {
            return prepare(runs).map(Refinement.Change::replacement);
        }

        public Optional<Refinement.Change> prepare(long runs) {
            var output = recipe.outputs().getFirst();
            var materials = materials(runs);
            Produce demand = Produce.of(workshop.id(), workshop.site(), output.spec(), output.count() * runs,
                (at, inputs, yield, vocation, limit) -> Math.min(runs, limit));
            return workshop.options(demand, Grown.of(demand)).stream()
                .filter(option -> WorkshopRecipes.materials(option.replacement()).equals(materials)).findFirst()
                .flatMap(Refinements::expand);
        }

        public WorkshopRecipes.Recipe materials(long runs) {
            return new WorkshopRecipes.Recipe(recipe.inputs().stream()
                .map(need -> new Need(need.spec(), Math.multiplyExact(need.count(), runs))).toList(),
                recipe.outputs().stream()
                    .map(amount -> new Amount(amount.spec(), Math.multiplyExact(amount.count(), runs))).toList());
        }
    }

    private final Colony colony;
    private final Map<Workshop, List<Entry>> cached = new HashMap<>();
    private final Map<Object, Set<ItemSpec>> products = new HashMap<>();
    private List<Entry> entries = List.of();
    private boolean ready;

    public ColonyRecipes(Colony colony) {
        this.colony = colony;
    }

    public boolean ready() {
        return ready;
    }

    public void refresh(MinecraftServer server) {
        Map<Workshop, List<Entry>> current = new HashMap<>();
        List<Entry> found = new ArrayList<>();
        for (var view : colony.views(server)) {
            var level = view.level();
            Set<ItemSpec> candidates = products.computeIfAbsent(level.getRecipeManager(),
                ignored -> WorkshopRecipes.products(level));
            for (Workshop workshop : colony.workshopsIn(view.dimension())) {
                if (workshop.id().toString().equals("folkways_core:ae2_network")
                        || workshop.id().toString().equals("folkways_core:rs2_network")) {
                    continue;
                }
                List<Entry> recipes = cached.computeIfAbsent(workshop, ignored -> workshop.recipes(candidates)
                    .stream().map(recipe -> new Entry(PatternTimes.identity(colony.id() + "/" + workshop.id()
                        + "/" + workshop.site().where() + "/" + recipe), workshop, recipe)).toList());
                current.put(workshop, recipes);
                found.addAll(recipes);
            }
        }
        cached.keySet().retainAll(current.keySet());
        entries = found.stream().distinct().sorted(Comparator.comparing(Entry::reference)).toList();
        ready = true;
    }

    public List<Entry> entries() {
        return entries;
    }

    public Optional<Entry> forInputs(List<ItemStack> goods) {
        var matches = entries.stream().filter(entry -> matches(entry.recipe(), goods)).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static boolean matches(WorkshopRecipes.Recipe recipe, List<ItemStack> goods) {
        long[] remaining = goods.stream().mapToLong(ItemStack::getCount).toArray();
        for (var need : recipe.inputs()) {
            long left = need.count();
            for (int slot = 0; slot < goods.size(); slot++) {
                if (Goods.matches(goods.get(slot), need.spec())) {
                    long take = Math.min(left, remaining[slot]);
                    remaining[slot] -= take;
                    left -= take;
                }
            }
            if (left > 0) {
                return false;
            }
        }
        return Arrays.stream(remaining).allMatch(count -> count == 0);
    }

    public Optional<Entry> find(String reference) {
        return entries.stream().filter(entry -> entry.reference().equals(reference)).findFirst();
    }
}
