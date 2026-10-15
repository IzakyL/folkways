package io.github.izakyl.folkways.plugins.cookery.tray;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.BambooTrayRecipe;
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
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

public final class TrayStation implements KitchenStation {
    public boolean matches(BlockState state) { return state.is(ModBlocks.BAMBOO_TRAY.get()); }
    static Optional<BambooTrayBlockEntity> tray(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof BambooTrayBlockEntity tray
            ? Optional.of(tray) : Optional.empty();
    }
    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        var found = tray(level, at);
        if (found.isEmpty() || TrayBatch.read(found.get(), level).isPresent()) { return Optional.empty(); }
        int slot = -1;
        for (int index = 0; index < 4; index++) { if (found.get().getItem(index).isEmpty()) { slot = index; break; } }
        if (slot < 0) { return Optional.empty(); }
        List<Job> jobs = level.getRecipeManager().getAllRecipesFor(ModRecipes.BAMBOO_TRAY_RECIPE).stream()
            .filter(holder -> !FolkwaysConfig.refusedRecipes().contains(holder.id()))
            .flatMap(holder -> java.util.Arrays.stream(holder.value().getIngredient().getItems()).filter(stack -> !stack.isEmpty()).map(stack ->
                new Job(holder.id(), Goods.specOf(stack), holder.value().getResultItem(level.registryAccess()), holder.value().getSubtype(),
                    holder.value().getDuration(), Math.min(stack.getMaxStackSize(), holder.value().getResult().getMaxStackSize() / holder.value().getResult().getCount()))))
            .filter(job -> !job.output().isEmpty() && job.capacity() > 0).toList();
        return Optional.of(new Bench(key, colony, at, slot, stances, jobs, bookings, changed));
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        if (!bookings.free(at)) { return List.of(); }
        return tray(level, at).flatMap(tray -> TrayBatch.read(tray, level)).filter(batch -> batch.colony().equals(colony)).map(batch -> {
            int count = batch.input().getCount();
            Job job = new Job(batch.recipe(), Goods.specOf(batch.input()), batch.output().copyWithCount(batch.output().getCount() / count),
                batch.subtype(), batch.time(), count);
            return List.of(Grown.of(new TrayWork(colony, at, batch.slot(), stances, job, count, bookings, changed, batch)));
        }).orElse(List.of());
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        tray(level, at).filter(tray -> TrayBatch.ours(tray, colony)).ifPresent(TrayBatch::clear);
    }
    public Component status(ServerLevel level, WorldPos at) {
        var found = tray(level, at);
        if (found.isEmpty()) { return Component.translatable("folkways.page.cookery.idle"); }
        var batch = TrayBatch.read(found.get(), level);
        String status = batch.map(owned -> TrayBatch.ready(found.get(), level, owned) ? "finished"
            : TrayWeather.allows(level, at.block(level), owned.subtype()) ? "cooking" : "weather").orElse("idle");
        return Component.translatable("folkways.page.cookery." + status);
    }
    record Job(ResourceLocation recipe, ItemSpec input, ItemStack output, BambooTrayRecipe.Subtype subtype, int time, int capacity) {
        Job { output = output.copy(); }
    }
    private record Bench(UUID key, UUID colony, WorldPos at, int slot, Stances stances, List<Job> jobs,
                         KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) { return List.of(); }
            List<Refinement.Change> options = new ArrayList<>();
            for (Job job : jobs) {
                if (!Goods.matches(job.output(), produce.wanted())) { continue; }
                int count = (int) produce.runs(site(), List.of(new Need(job.input(), 1)), job.output().getCount(), Optional.of(WaresContent.trade()), job.capacity());
                var work = new TrayWork(colony, at, slot, stances, job, count, bookings, changed, null);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(options);
        }
    }
}
