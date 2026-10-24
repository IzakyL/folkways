package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.MillstoneRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.IngredientSpecs;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStation;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.state.BlockState;

public final class MillstoneStation implements KitchenStation {
    private record RecoveryKey(UUID colony, WorldPos at) { }
    private record Kept(Grown goal, long generation) { }
    private static final Map<RecoveryKey, Kept> RECOVERIES = new ConcurrentHashMap<>();

    static void keep(UUID colony, WorldPos at, Grown goal, long generation) {
        RECOVERIES.put(new RecoveryKey(colony, at), new Kept(goal, generation));
    }
    static void ended(UUID colony, WorldPos at, Node node) {
        RECOVERIES.computeIfPresent(new RecoveryKey(colony, at), (key, kept) ->
            kept.goal().nodes().getFirst() == node ? null : kept);
    }
    private static List<Grown> active(UUID colony, WorldPos at, KitchenBookings bookings) {
        RecoveryKey key = new RecoveryKey(colony, at);
        Kept kept = RECOVERIES.get(key);
        if (kept == null) { return List.of(); }
        Node node = kept.goal().nodes().getFirst();
        if (bookings.valid(at, node.id(), kept.generation()) && bookings.first(at, node.id())) {
            return List.of(kept.goal());
        }
        RECOVERIES.remove(key, kept);
        return List.of();
    }

    @Override
    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        return at.in(level) ? active(colony, at, bookings) : List.of();
    }
    public boolean matches(BlockState state) { return state.is(ModBlocks.MILLSTONE.get()); }
    public BlockPos anchor(Level level, BlockPos cell) {
        var part = level.getBlockState(cell).getValue(MillstoneBlock.PART);
        return cell.offset(-part.getPosX(), 0, -part.getPosY());
    }
    public Optional<Stances> stances(ServerLevel level, WorldPos at) {
        BlockPos center = at.block(level);
        return Stances.of(level, Reach.footingsAround(level, center, 4, 2).stream()
            .filter(feet -> Reach.withinReach(feet, center) && inSight(level, feet, center)).toList());
    }
    static boolean inSight(ServerLevel level, BlockPos feet, BlockPos center) {
        if (Reach.inSight(level, feet, center)) { return true; }
        var hit = level.clip(new ClipContext(Vec3.atBottomCenterOf(feet).add(0, Reach.EYE_HEIGHT, 0),
            Vec3.atCenterOf(center), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.BLOCK) { return false; }
        BlockPos struck = hit.getBlockPos();
        var state = level.getBlockState(struck);
        if (!state.is(ModBlocks.MILLSTONE.get())) { return false; }
        var part = state.getValue(MillstoneBlock.PART);
        return struck.offset(-part.getPosX(), 0, -part.getPosY()).equals(center);
    }    static Optional<MillstoneBlockEntity> mill(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level))
                && level.getBlockEntity(at.block(level)) instanceof MillstoneBlockEntity mill
            ? Optional.of(mill) : Optional.empty();
    }
    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        if (mill(level, at).filter(mill -> (!mill.hasEntity() || MillstoneDrive.external(level, mill)) && mill.getInput().isEmpty()
                && mill.isOutputEmpty() && MillstoneBatch.read(mill, level).isEmpty()).isEmpty()) {
            return Optional.empty();
        }
        List<Job> jobs = level.getRecipeManager().getAllRecipesFor(ModRecipes.MILLSTONE_RECIPE).stream()
            .filter(holder -> !FolkwaysVanillaConfig.refusedRecipes().contains(holder.id()))
            .flatMap(holder -> Job.of(holder.id(), holder.value()).stream().flatMap(job ->
                IngredientSpecs.choices(holder.value().ingredient()).stream().map(input ->
                    new Job(job.recipe(), input, job.guaranteed(), job.possible())))).toList();
        return Optional.of(new Bench(key, colony, at, stances, jobs, bookings, changed));
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        List<Grown> active = active(colony, at, bookings);
        if (!active.isEmpty()) { return active; }
        if (!bookings.free(at)) {
            return List.of();
        }
        return mill(level, at).flatMap(mill -> MillstoneBatch.read(mill, level)
            .filter(batch -> batch.colony().equals(colony)).map(batch -> {
                List<ItemStack> outputs = new ArrayList<>();
                for (int slot = 0; slot < mill.getOutputs().getSlots(); slot++) {
                    ItemStack stack = mill.getOutputs().getStackInSlot(slot);
                    if (!stack.isEmpty()) { outputs.add(stack.copy()); }
                }
                Job job = batch.ready() ? new Job(batch.recipe(), ItemSpec.of(ResourceLocation.withDefaultNamespace("air")), outputs, outputs) : level.getRecipeManager().byKey(batch.recipe())
                    .filter(holder -> holder.value() instanceof MillstoneRecipe)
                    .flatMap(holder -> Job.of(holder.id(), (MillstoneRecipe) holder.value()))
                    .orElse(new Job(batch.recipe(), ItemSpec.of(ResourceLocation.withDefaultNamespace("air")), List.of(), outputs));
                return List.of(new MillstoneWork(colony, at, stances, job,
                    Math.max(1, mill.getInput().getCount()), bookings, changed, batch).recoveryGoal());
            })).orElse(List.of());
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        RECOVERIES.remove(new RecoveryKey(colony, at));
        MillstoneDrive.release(colony, level, at);
        mill(level, at).filter(mill -> MillstoneBatch.ours(mill, colony)).ifPresent(MillstoneBatch::clear);
    }
    public void tick(ServerLevel level, UUID colony) { MillstoneDrive.tick(level, colony); }
    public void stop(UUID colony, Closing why) {
        RECOVERIES.keySet().removeIf(key -> key.colony().equals(colony));
        MillstoneDrive.stop(colony);
    }
    public Component status(ServerLevel level, WorldPos at) {
        if (MillstoneDrive.blocked(level, at)) {
            return Component.translatable("folkways.page.cookery.exit_blocked");
        }
        return Component.translatable("folkways.page.cookery." + mill(level, at)
            .map(mill -> !mill.isOutputEmpty() ? "finished" : mill.hasEntity() ? "cooking" : "idle").orElse("idle"));
    }

    record Job(ResourceLocation recipe, ItemSpec input, List<ItemStack> guaranteed, List<ItemStack> possible) {
        Job {
            guaranteed = guaranteed.stream().map(ItemStack::copy).toList();
            possible = possible.stream().map(ItemStack::copy).toList();
        }
        static Optional<Job> of(ResourceLocation id, MillstoneRecipe recipe) {
            Optional<ItemSpec> input = IngredientSpecs.of(recipe.ingredient());
            List<ItemStack> certain = recipe.results().stream().filter(out -> out.chance() >= 1)
                .map(out -> out.stack().copy()).toList();
            return input.isEmpty() || certain.isEmpty() ? Optional.empty()
                : Optional.of(new Job(id, input.get(), certain,
                    recipe.results().stream().map(out -> out.stack().copy()).toList()));
        }
        int capacity() {
            int limit = 1;
            for (int input = 1; input <= 8; input++) {
                int slots = 0;
                for (ItemStack output : possible) {
                    int cap = Math.min(64, output.getMaxStackSize());
                    slots += (output.getCount() * input + cap - 1) / cap;
                }
                if (slots <= 4) { limit = input; }
            }
            return limit;
        }
        List<Amount> amounts(int count, boolean all) {
            return (all ? possible : guaranteed).stream()
                .map(stack -> new Amount(Goods.specOf(stack), (long) stack.getCount() * count)).toList();
        }
    }
    private record Bench(UUID key, UUID colony, WorldPos at, Stances stances, List<Job> jobs,
                         KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) { return List.of(); }
            List<Refinement.Change> answers = new ArrayList<>();
            for (Job job : jobs) {
                long yield = job.guaranteed().stream().filter(stack -> Goods.matches(stack, produce.wanted()))
                    .mapToLong(ItemStack::getCount).sum();
                if (yield < 1) { continue; }
                int count = (int) produce.runs(site(), List.of(new io.github.izakyl.folkways.core.api.work.Need(job.input(), 1)),
                    yield, Optional.of(WaresContent.trade()), job.capacity());
                MillstoneWork work = new MillstoneWork(colony, at, stances, job, count, bookings, changed, null);
                answers.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id(), work.spec().estimate())));
            }
            return List.copyOf(answers);
        }
    }
}
