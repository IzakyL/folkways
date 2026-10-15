package io.github.izakyl.folkways.plugins.cookery.steamer;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.SteamerBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
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
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class SteamerStation implements KitchenStation {
    public boolean matches(BlockState state) { return state.is(ModBlocks.STEAMER.get()); }
    public BlockPos anchor(Level level, BlockPos cell) {
        BlockPos base = cell;
        while (base.getY() > level.getMinBuildHeight() && level.getBlockState(base.below()).is(ModBlocks.STEAMER.get())) { base = base.below(); }
        return base.immutable();
    }
    static List<WorldPos> parts(ServerLevel level, WorldPos base) {
        List<WorldPos> parts = new ArrayList<>();
        for (WorldPos part = base; steamer(level, part).isPresent(); part = part.above()) { parts.add(part); }
        return List.copyOf(parts);
    }
    static Optional<SteamerBlockEntity> steamer(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof SteamerBlockEntity steamer
            ? Optional.of(steamer) : Optional.empty();
    }
    static boolean accessible(ServerLevel level, BlockPos part) {
        BlockPos above = part.above();
        return !level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN);
    }
    public static boolean heat(ServerLevel level, BlockPos part) {
        var be = level.getBlockEntity(part);
        if (!(be instanceof SteamerBlockEntity steamer)) { return false; }
        if (steamer.hasHeatSource(level)) { return true; }
        for (int depth = 1; depth < SteamerBlockEntity.MAX_LIT_LEVEL; depth++) {
            if (!(level.getBlockEntity(part.below(depth)) instanceof SteamerBlockEntity below)
                    || below.getBlockState().getValue(SteamerBlock.HALF)) { return false; }
            if (below.hasHeatSource(level)) { return true; }
        }
        return false;
    }
    public static boolean ownedWithoutHeat(SteamerBlockEntity steamer, ServerLevel level) {
        return SteamerBatch.marked(steamer) && SteamerBatch.read(steamer, level).isPresent() && !heat(level, steamer.getBlockPos());
    }
    static boolean ripe(SteamerBlockEntity steamer) {
        boolean any = false;
        for (int slot = 0; slot < 8; slot++) {
            if (steamer.getItems().get(slot).isEmpty()) { continue; }
            any = true;
            if (steamer.getCookingTime()[slot] != -1) { return false; }
        }
        return any;
    }
    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        List<WorldPos> parts = parts(level, at);
        if (parts.isEmpty() || parts.stream().anyMatch(part -> steamer(level, part).flatMap(be -> SteamerBatch.read(be, level)).isPresent())) {
            return Optional.empty();
        }
        var empty = parts.stream().filter(part -> accessible(level, part.block(level))
            && steamer(level, part).orElseThrow().getItems().stream().allMatch(ItemStack::isEmpty)).findFirst();
        if (empty.isEmpty()) { return Optional.empty(); }
        WorldPos part = empty.get();
        int capacity = level.getBlockState(part.block(level)).getValue(SteamerBlock.HALF) ? 4 : 8;
        List<Job> jobs = level.getRecipeManager().getAllRecipesFor(ModRecipes.STEAMER_RECIPE).stream()
            .filter(holder -> !FolkwaysConfig.refusedRecipes().contains(holder.id()))
            .flatMap(holder -> java.util.Arrays.stream(holder.value().getIngredient().getItems()).filter(stack -> !stack.isEmpty()).map(stack ->
                new Job(holder.id(), Goods.specOf(stack), holder.value().getResultItem(level.registryAccess()), holder.value().getCookTick())))
            .filter(job -> !job.output().isEmpty()).toList();
        return Optional.of(new Bench(key, colony, at, part, stances, jobs, capacity, bookings, changed));
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        if (!bookings.free(at)) { return List.of(); }
        for (WorldPos part : parts(level, at)) {
            var be = steamer(level, part).orElseThrow();
            var batch = SteamerBatch.read(be, level).filter(owned -> owned.colony().equals(colony));
            if (batch.isEmpty()) { continue; }
            CompoundRecovery recovered = recoveryProducts(be, level);
            Job job = new Job(batch.get().recipe(), ItemSpec.of(ResourceLocation.withDefaultNamespace("air")), recovered.output(), 0);
            return List.of(Grown.of(new SteamerWork(colony, at, part, stances, job, recovered.count(), bookings, changed, batch.get())));
        }
        return List.of();
    }
    private static CompoundRecovery recoveryProducts(SteamerBlockEntity steamer, ServerLevel level) {
        var tag = steamer.getPersistentData().getCompound("folkways.cookery.steamer").getList("outputs", net.minecraft.nbt.Tag.TAG_COMPOUND);
        ItemStack output = ItemStack.EMPTY;
        int count = 0;
        for (int slot = 0; slot < tag.size(); slot++) {
            ItemStack stack = ItemStack.parseOptional(level.registryAccess(), tag.getCompound(slot));
            if (!stack.isEmpty()) { output = stack; count++; }
        }
        return new CompoundRecovery(output, count);
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        for (WorldPos part : parts(level, at)) {
            steamer(level, part).filter(be -> SteamerBatch.ours(be, colony)).ifPresent(be -> {
                SteamerBatch.restoreLid(be, level);
                SteamerBatch.clear(be);
            });
        }
    }
    public Component status(ServerLevel level, WorldPos at) {
        boolean any = false;
        boolean warm = false;
        for (WorldPos part : parts(level, at)) {
            var be = steamer(level, part).orElseThrow();
            if (ripe(be)) { return Component.translatable("folkways.page.cookery.finished"); }
            any |= be.getItems().stream().anyMatch(stack -> !stack.isEmpty());
            warm |= heat(level, part.block(level));
        }
        return Component.translatable("folkways.page.cookery." + (any ? warm ? "cooking" : "cold" : "idle"));
    }
    record Job(ResourceLocation recipe, ItemSpec input, ItemStack output, int time) {
        Job { output = output.copy(); }
    }
    private record CompoundRecovery(ItemStack output, int count) { }
    private record Bench(UUID key, UUID colony, WorldPos at, WorldPos part, Stances stances, List<Job> jobs,
                         int capacity, KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) { return List.of(); }
            List<Refinement.Change> options = new ArrayList<>();
            for (Job job : jobs) {
                if (!Goods.matches(job.output(), produce.wanted())) { continue; }
                int count = (int) produce.runs(site(), List.of(new Need(job.input(), 1)), job.output().getCount(), Optional.of(WaresContent.trade()), capacity);
                var work = new SteamerWork(colony, at, part, stances, job, count, bookings, changed, null);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(options);
        }
    }
}
