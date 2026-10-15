package io.github.izakyl.folkways.plugins.cookery.spit;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.ShawarmaSpitBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.plugins.cookery.CookeryContent;
import io.github.izakyl.folkways.plugins.cookery.KitchenBookings;
import io.github.izakyl.folkways.plugins.cookery.KitchenStation;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

public final class SpitStation implements KitchenStation {
    public boolean matches(BlockState state) { return state.is(ModBlocks.SHAWARMA_SPIT.get()); }
    public BlockPos anchor(Level level, BlockPos cell) {
        return level.getBlockState(cell).getValue(ShawarmaSpitBlock.HALF) == DoubleBlockHalf.UPPER ? cell.below() : cell;
    }
    static List<WorldPos> slots(ServerLevel level, WorldPos anchor) {
        return List.of(anchor, anchor.above()).stream().filter(at -> spit(level, at).isPresent()).toList();
    }
    static Optional<ShawarmaSpitBlockEntity> spit(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level))
                && level.getBlockEntity(at.block(level)) instanceof ShawarmaSpitBlockEntity spit
            ? Optional.of(spit) : Optional.empty();
    }
    public Optional<Stances> stances(ServerLevel level, WorldPos at) {
        return KitchenStation.around(level, slots(level, at));
    }
    private record Approach(WorldPos part, Stances stances) { }
    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        List<WorldPos> parts = slots(level, at);
        if (parts.stream().anyMatch(part -> spit(level, part).flatMap(spit -> SpitBatch.read(spit, level)).isPresent())) {
            return Optional.empty();
        }
        Optional<Approach> available = parts.stream().filter(part -> spit(level, part)
            .filter(spit -> spit.cookingItem.isEmpty() && spit.cookedItem.isEmpty()).isPresent())
            .flatMap(part -> KitchenStation.reaching(level, stances, List.of(part))
                .map(working -> new Approach(part, working)).stream()).findFirst();
        if (available.isEmpty()) { return Optional.empty(); }
        List<Job> jobs = level.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING).stream()
            .filter(holder -> !FolkwaysConfig.refusedRecipes().contains(holder.id()))
            .flatMap(holder -> holder.value().getIngredients().stream().flatMap(ingredient ->
                java.util.Arrays.stream(ingredient.getItems()).filter(stack -> !stack.isEmpty()).map(stack ->
                    new Job(holder.id(), Goods.specOf(stack), holder.value().getResultItem(level.registryAccess()).copyWithCount(1),
                        holder.value().getCookingTime())))).filter(job -> !job.output().isEmpty()).toList();
        return Optional.of(new Bench(key, colony, at, available.get().part(), available.get().stances(), jobs, bookings, changed));
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        if (!bookings.free(at)) { return List.of(); }
        for (WorldPos part : slots(level, at)) {
            var spit = spit(level, part).orElseThrow();
            var owned = SpitBatch.read(spit, level).filter(batch -> batch.colony().equals(colony));
            var working = KitchenStation.reaching(level, stances, List.of(part));
            if (owned.isPresent() && working.isPresent()) {
                Job job = new Job(owned.get().recipe(), ItemSpec.of(ResourceLocation.withDefaultNamespace("air")),
                    spit.cookedItem.copyWithCount(1), Math.max(0, spit.cookTime));
                return List.of(Grown.of(new SpitWork(colony, at, part, working.orElseThrow(), job, spit.cookedItem.getCount(),
                    bookings, changed, owned.get())));
            }
        }
        return List.of();
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        for (WorldPos part : slots(level, at)) {
            spit(level, part).filter(spit -> SpitBatch.ours(spit, colony)).ifPresent(SpitBatch::clear);
        }
    }
    public Component status(ServerLevel level, WorldPos at) {
        boolean cooking = false;
        boolean powered = false;
        for (WorldPos part : slots(level, at)) {
            var spit = spit(level, part).orElseThrow();
            if (!spit.cookedItem.isEmpty() && spit.cookTime <= 0 && spit.cookingItem.isEmpty()) {
                return Component.translatable("folkways.page.cookery.finished");
            }
            cooking |= !spit.cookingItem.isEmpty();
            powered |= spit.getBlockState().getValue(ShawarmaSpitBlock.POWERED);
        }
        return Component.translatable("folkways.page.cookery." + (cooking ? powered ? "cooking" : "cold" : "idle"));
    }
    record Job(ResourceLocation recipe, ItemSpec input, ItemStack output, int time) {
        Job { output = output.copy(); }
    }
    private record Bench(UUID key, UUID colony, WorldPos at, WorldPos part, Stances stances, List<Job> jobs,
                         KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) { return List.of(); }
            List<Refinement.Change> result = new ArrayList<>();
            for (Job job : jobs) {
                if (!Goods.matches(job.output(), produce.wanted())) { continue; }
                int count = (int) produce.runs(site(), List.of(new Need(job.input(), 1)), 1,
                    Optional.of(WaresContent.trade()), 8);
                SpitWork work = new SpitWork(colony, at, part, stances, job, count, bookings, changed, null);
                result.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(result);
        }
    }
}
