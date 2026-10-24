package io.github.izakyl.folkways.cookery.board;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ChoppingBoardBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.ChoppingBoardRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class ChoppingBoardWorkshops {

    private ChoppingBoardWorkshops() {
    }

    public static Optional<Workshop> freeze(UUID key, UUID colonyId, ServerLevel level, WorldPos at,
                                           Stances stances, KitchenBookings bookings, Runnable changed) {
        Optional<ChoppingBoardBlockEntity> board = board(level, at);
        if (board.isEmpty() || !board.get().getCurrentCutStack().isEmpty()) {
            return Optional.empty();
        }
        List<Job> jobs = level.getRecipeManager().getAllRecipesFor(ModRecipes.CHOPPING_BOARD_RECIPE).stream()
            .filter(holder -> !FolkwaysVanillaConfig.refusedRecipes().contains(holder.id()))
            .sorted(Comparator.comparing(holder -> holder.id().toString()))
            .map(Job::of).flatMap(Optional::stream).toList();
        return Optional.of(new Bench(key, colonyId, at, stances, jobs, bookings, changed));
    }

    public static List<Grown> recovery(UUID colonyId, ServerLevel level, WorldPos at, Stances stances,
                                       KitchenBookings bookings, Runnable changed) {
        if (!bookings.free(at)) {
            return List.of();
        }
        Optional<ChoppingBoardBlockEntity> board = board(level, at);
        if (board.isEmpty()) {
            return List.of();
        }
        Optional<BoardOwnership.Batch> batch = BoardOwnership.read(board.get(), level)
            .filter(owned -> owned.colony().equals(colonyId));
        if (batch.isEmpty()) {
            return List.of();
        }
        BoardOwnership.Batch owned = batch.get();
        Job job = Job.recover(owned.recipe(), board.get(), level);
        BoardWork work = new BoardWork(colonyId, at, stances, job, bookings, changed, owned);
        return List.of(Grown.of(work));
    }

    public static void release(UUID colonyId, ServerLevel level, WorldPos at) {
        board(level, at).filter(board -> BoardOwnership.ours(board, colonyId)).ifPresent(BoardOwnership::clear);
    }
    static Optional<ChoppingBoardBlockEntity> board(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level))
            && level.getBlockEntity(at.block(level)) instanceof ChoppingBoardBlockEntity board
            ? Optional.of(board) : Optional.empty();
    }

    record Job(ResourceLocation recipe, ItemSpec input, List<ItemStack> results, int cuts,
               ResourceLocation model) {

        Job {
            results = results.stream().map(ItemStack::copy).toList();
        }

        static Optional<Job> of(RecipeHolder<ChoppingBoardRecipe> holder) {
            ChoppingBoardRecipe recipe = holder.value();
            List<ItemSpec> inputs = Stream.of(recipe.getIngredient().getItems()).filter(stack -> !stack.isEmpty())
                .map(Goods::specOf).distinct().toList();
            List<ItemStack> outputs = recipe.getResults().stream().filter(stack -> !stack.isEmpty()).toList();
            return inputs.isEmpty() || outputs.isEmpty() ? Optional.empty()
                : Optional.of(new Job(holder.id(), ItemSpec.anyOf(inputs), outputs,
                    recipe.getCutCount(), recipe.getModelId()));
        }

        static Job recover(ResourceLocation recipe, ChoppingBoardBlockEntity board, ServerLevel level) {
            return new Job(recipe, Goods.specOf(board.getCurrentCutStack()),
                BoardOwnership.results(board, level), board.getMaxCutCount(), board.getModelId());
        }

        List<Amount> gives() {
            Map<ItemSpec, Long> amounts = new LinkedHashMap<>();
            results.forEach(stack -> amounts.merge(Goods.specOf(stack), (long) stack.getCount(), Long::sum));
            return amounts.entrySet().stream().map(entry -> new Amount(entry.getKey(), entry.getValue())).toList();
        }

        long yield(ItemSpec goal) {
            return results.stream().filter(stack -> Goods.matches(stack, goal)).mapToLong(ItemStack::getCount).sum();
        }

        boolean matches(ChoppingBoardRecipe current) {
            List<ItemStack> outputs = current.getResults();
            if (cuts != current.getCutCount() || !model.equals(current.getModelId())
                    || outputs.size() != results.size()) {
                return false;
            }
            for (int index = 0; index < results.size(); index++) {
                if (!ItemStack.matches(results.get(index), outputs.get(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private record Bench(UUID key, UUID colonyId, WorldPos at, Stances stances, List<Job> jobs,
                         KitchenBookings bookings, Runnable changed) implements Workshop {

        @Override
        public ResourceLocation id() {
            return CookeryContent.ID;
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
            List<Refinement.Change> options = new ArrayList<>();
            for (Job job : jobs) {
                long yield = job.yield(produce.wanted());
                if (yield <= 0) {
                    continue;
                }
                BoardWork work = new BoardWork(colonyId, at, stances, job, bookings, changed, null);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id(), work.spec().estimate())));
            }
            return List.copyOf(options);
        }
    }
}
