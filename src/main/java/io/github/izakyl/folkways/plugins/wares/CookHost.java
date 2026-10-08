package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Hold;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;

final class CookHost implements StationHost {

    private static final long SMALLEST_LOAD = 8;

    private final UUID id = UUID.randomUUID();
    private final WorldPos at;
    private final Block block;
    private final RecipeType<? extends AbstractCookingRecipe> recipeType;
    private final Stances stances;

    private final Bookings spokenFor;
    private final Function<Block, Set<WorldPos>> kin;
    private final Runnable spoken;

    CookHost(WorldPos at, Block block, RecipeType<? extends AbstractCookingRecipe> recipeType,
             Stances stances, Bookings spokenFor, Function<Block, Set<WorldPos>> kin, Runnable spoken) {
        this.spoken = spoken;
        this.kin = kin;
        this.at = at;
        this.block = block;
        this.recipeType = recipeType;
        this.stances = stances;
        this.spokenFor = spokenFor;
    }

    @Override
    public Stances stances() {
        return stances;
    }

    @Override
    public Workshop frozen(RecipeIndex recipes, ServerLevel level, Optional<ItemSpec> fuel) {
        return new Oven(id, at, block, recipeType, stances, recipes, spokenFor, kin, spoken,
            fuel.flatMap(spec -> stoking(level, spec)));
    }

    // The colony's fuel as this cooker burns it, and how long what its fuel slot holds now burns for.
    private Optional<Stoking> stoking(ServerLevel level, ItemSpec fuel) {
        int burns = Goods.members(fuel).stream().findFirst()
            .map(item -> new ItemStack(item).getBurnTime(recipeType)).orElse(0);
        if (burns <= 0) {
            return Optional.empty();
        }
        long stocked = Machines.at(level, at.block(level))
            .filter(machine -> machine.getContainerSize() > Machines.FUEL_SLOT)
            .map(machine -> machine.getItem(Machines.FUEL_SLOT))
            .map(held -> (long) held.getBurnTime(recipeType) * held.getCount())
            .orElse(0L);
        return Optional.of(new Stoking(fuel, burns, stocked));
    }

    record Stoking(ItemSpec fuel, int burns, long stocked) {

        // The fuel to bring for `ticks` of cooking, past what the slot holds already unless that is spoken for.
        Optional<Need> toCook(long ticks, boolean spoken) {
            long wanting = ticks - (spoken ? 0 : stocked);
            if (wanting <= 0) {
                return Optional.empty();
            }
            long items = Math.min((wanting + burns - 1) / burns, Math.max(1, Goods.stackSize(fuel)));
            return Optional.of(new Need(fuel, items));
        }
    }

    Block block() {
        return block;
    }

    record Firing(ItemSpec takes, ItemSpec makes, long perRun, long perLoad, int cookTicks) {

        // The most one cooker cooks at once: one stack of input, and no more than one stack of what it makes.
        long mostPerLoad() {
            return Math.max(1, Math.min(perLoad, Goods.stackSize(makes) / Math.max(1, perRun)));
        }

        static Firing of(ItemSpec takes, ItemStack result, int cookTicks) {
            return new Firing(takes, Goods.specOf(result), result.getCount(),
                Math.max(1, Goods.stackSize(takes)), cookTicks);
        }
    }

    private record Oven(UUID key, WorldPos at, Block block,
                        RecipeType<? extends AbstractCookingRecipe> recipeType, Stances stances,
                        RecipeIndex recipes, Bookings spokenFor, Function<Block, Set<WorldPos>> kin,
                        Runnable spoken, Optional<Stoking> stoking) implements Workshop {

        @Override
        public ResourceLocation id() {
            return WaresContent.ID;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(at);
        }

        @Override
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) {
                return List.of();
            }
            List<Refinement.Change> answers = new ArrayList<>();
            int idle = 0;
            for (WorldPos other : kin.apply(block)) {
                idle += spokenFor.taken(other) ? 0 : 1;
            }
            boolean shared = spokenFor.taken(at);
            for (Firing firing : firings(produce.wanted())) {
                long most = firing.mostPerLoad();
                long room = spokenFor.room(at, firing.makes(), most);
                long enough = (produce.count() + firing.perRun() - 1) / firing.perRun();
                long spread = Math.max(SMALLEST_LOAD, (enough + Math.max(1, idle) - 1) / Math.max(1, idle));
                long runs = produce.runs(site(), List.of(new Need(firing.takes(), 1)), firing.perRun(),
                    Optional.empty(), Math.min(room, spread));
                int load = (int) Math.min(runs, Integer.MAX_VALUE);
                long cooking = (long) load * Math.max(1, firing.cookTicks());
                UUID taker = UUID.randomUUID();
                Smelt smelt = Smelt.of(taker, at, block, stances, firing.takes(), load, firing.makes(),
                    firing.perRun() * load, firing.cookTicks(), spokenFor.ahead(at, firing.makes(), most),
                    stoking.flatMap(fuel -> fuel.toCook(cooking, shared)), () -> spokenFor.out(at, taker));
                answers.add(Refinement.Change.of(Grown.of(smelt))
                    .holding(new Spoken(taker, firing.makes(), load, cooking, most)));
            }
            return List.copyOf(answers);
        }

        // The cooker spoken for one load, in its turn: the load goes in once every batch booked before it is out.
        private final class Spoken implements Hold {

            private final UUID taker;
            private final ItemSpec makes;
            private final long load;
            private final long ticks;
            private final long most;
            private Set<UUID> after = Set.of();
            private boolean held;

            Spoken(UUID taker, ItemSpec makes, long load, long ticks, long most) {
                this.taker = taker;
                this.makes = makes;
                this.load = load;
                this.ticks = ticks;
                this.most = most;
            }

            @Override
            public Optional<RefusalKind> take() {
                Optional<Set<UUID>> booked = held ? Optional.empty()
                    : spokenFor.book(at, taker, makes, load, ticks, most);
                if (booked.isEmpty()) {
                    return Optional.of(WaresRefusal.STATION_BUSY);
                }
                after = Set.copyOf(booked.get());
                held = true;
                spoken.run();
                return Optional.empty();
            }

            @Override
            public Set<UUID> after() {
                return after;
            }

            @Override
            public void release(Ending how) {
                if (held) {
                    held = false;
                    after = Set.of();
                    if (spokenFor.release(at, taker)) {
                        spoken.run();
                    }
                }
            }
        }

        @SuppressWarnings("unchecked")
        private List<Firing> firings(ItemSpec goal) {
            List<Firing> found = new ArrayList<>();
            for (RecipeHolder<AbstractCookingRecipe> holder
                    : recipes.producing((RecipeType<AbstractCookingRecipe>) recipeType, goal)) {
                AbstractCookingRecipe recipe = holder.value();
                Optional<Ingredient> ingredient = recipe.getIngredients().stream()
                    .filter(candidate -> !candidate.isEmpty())
                    .findFirst();
                if (ingredient.isEmpty()) {
                    continue;
                }
                Optional<ItemSpec> takes = Ingredients.specOf(ingredient.get());
                ItemStack result = recipes.resultOf(recipe);
                if (takes.isEmpty() || result.isEmpty()) {
                    continue;
                }
                found.add(Firing.of(takes.get(), result, recipe.getCookingTime()));
            }
            return found;
        }
    }
}
