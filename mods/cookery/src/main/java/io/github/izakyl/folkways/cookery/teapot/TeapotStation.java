package io.github.izakyl.folkways.cookery.teapot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.ITeapot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.recipe.TeapotRecipe;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.TeapotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopecookery.item.TeacupItem;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStation;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;

public final class TeapotStation implements KitchenStation {
    // Only successfully planned autonomous recovery roots are kept here, never ordinary recipe descendants.
    private record RecoveryKey(UUID colony, WorldPos at) { }
    private static final java.util.Map<RecoveryKey, Grown> RECOVERING = new java.util.concurrent.ConcurrentHashMap<>();
    static void rememberRecovery(UUID colony, WorldPos at, TeapotWork work) { RECOVERING.put(new RecoveryKey(colony, at), work.recoveryGoal()); }
    static void endRecovery(UUID colony, WorldPos at, TeapotWork work) { RECOVERING.remove(new RecoveryKey(colony, at), work.recoveryGoal()); }
    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        if (!at.in(level)) { return List.of(); }
        RecoveryKey key = new RecoveryKey(colony, at);
        Grown active = RECOVERING.get(key);
        if (active == null) { return List.of(); }
        if (bookings.first(at, active.nodes().getFirst().id())) { return List.of(active); }
        RECOVERING.remove(key, active);
        return List.of();
    }
    public void stop(UUID colony, io.github.izakyl.folkways.core.api.colony.Closing why) {
        RECOVERING.keySet().removeIf(key -> key.colony().equals(colony));
    }

    public boolean matches(BlockState state) { return state.is(ModBlocks.TEAPOT.get()); }
    static Optional<TeapotBlockEntity> pot(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof TeapotBlockEntity pot ? Optional.of(pot) : Optional.empty();
    }
    static boolean empty(TeapotBlockEntity pot) {
        return pot.getStatus() == ITeapot.PUT_INGREDIENT && pot.getInput().isEmpty() && pot.getResult().isEmpty()
            && pot.getTeaFluidId().equals(TeapotRecipeSerializer.EMPTY_TEA_FLUID);
    }
    @Override
    public Optional<Stances> stances(ServerLevel level, WorldPos at) {
        return KitchenStation.super.stances(level, at).flatMap(offered -> servingStances(level, at, offered));
    }
    static Optional<Stances> servingStances(ServerLevel level, WorldPos at, Stances offered) {
        var working = new LinkedHashSet<WorldPos>();
        for (var cup : TeaCups.places(level, at.block(level))) {
            KitchenStation.reaching(level, offered, List.of(at, WorldPos.of(level, cup))).ifPresent(accepted ->
                accepted.cells().stream().filter(footing -> !footing.cell().equals(cup)).forEach(working::add));
        }
        return Stances.of(working);
    }
    static List<Job> jobs(ServerLevel level) {
        List<Job> jobs = new ArrayList<>(level.getRecipeManager().getAllRecipesFor(com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes.TEAPOT_RECIPE).stream()
            .filter(holder -> !FolkwaysVanillaConfig.refusedRecipes().contains(holder.id()) && holder.value().ingredientCount() == 1)
            .flatMap(holder -> java.util.Arrays.stream(holder.value().ingredient().getItems()).filter(stack -> !stack.isEmpty())
                .map(input -> new Job(holder.id(), holder.value().teaFluid(), input.copyWithCount(1),
                    holder.value().getResultItem(level.registryAccess()).copyWithCount(TeapotRecipe.OUTPUT_COUNT), holder.value().time())))
            .filter(job -> job.output().getItem() instanceof TeacupItem && !job.bucket().isEmpty()).toList());
        mystery(level).ifPresent(jobs::add);
        return List.copyOf(jobs);
    }
    static final ResourceLocation MYSTERY = ResourceLocation.fromNamespaceAndPath("folkways", "cookery/procedural/mystery_tea");
    static Optional<Job> mystery(ServerLevel level) {
        if (FolkwaysVanillaConfig.refusedRecipes().contains(MYSTERY)) return Optional.empty();
        var job = new Job(MYSTERY, ResourceLocation.withDefaultNamespace("water"),
            net.minecraft.world.item.Items.WHEAT_SEEDS.getDefaultInstance(),
            com.github.ysbbbbbb.kaleidoscopecookery.init.registry.TeacupRegistry.getItem(
                com.github.ysbbbbbb.kaleidoscopecookery.init.registry.TeacupRegistry.MYSTERY_TEA).getDefaultInstance().copyWithCount(4),
            TeapotRecipeSerializer.DEFAULT_TIME, Mode.MYSTERY);
        return job.selected(level, job.input()) ? Optional.of(job) : Optional.empty();
    }

    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        if (pot(level, at).filter(TeapotStation::empty).isEmpty()) { return Optional.empty(); }
        return servingStances(level, at, stances).map(working -> new Bench(key, colony, at, working, jobs(level), bookings, changed));
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        var active = activeRecovery(colony, level, at, bookings);
        if (!active.isEmpty()) { return active; }
        if (!bookings.free(at)) { return List.of(); }
        var working = servingStances(level, at, stances);
        if (working.isEmpty()) { return List.of(); }
        return pot(level, at).flatMap(pot -> TeaBatch.read(pot, level)).filter(batch -> batch.colony().equals(colony)).map(batch -> {
            Job job = new Job(batch.recipe(), batch.fluid(), batch.ingredient(), batch.output(), batch.time(), batch.mode());
            return List.of(new TeapotWork(colony, at, working.get(), job, bookings, changed, batch).recoveryGoal());
        }).orElse(List.of());
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        RECOVERING.remove(new RecoveryKey(colony, at)); pot(level, at).filter(pot -> TeaBatch.ours(pot, colony)).ifPresent(TeaBatch::clear); }
    public Component status(ServerLevel level, WorldPos at) {
        String state = pot(level, at).map(pot -> pot.getStatus() == ITeapot.FINISHED ? "finished" : empty(pot) ? "idle"
            : pot.hasHeatSource(level) ? "cooking" : "cold").orElse("idle");
        return Component.translatable("folkways.page.cookery." + state);
    }
    enum Mode { RECIPE, MYSTERY }
    record Job(ResourceLocation recipe, ResourceLocation fluid, ItemStack input, ItemStack output, int time, Mode mode) {
        Job(ResourceLocation recipe, ResourceLocation fluid, ItemStack input, ItemStack output, int time) {
            this(recipe, fluid, input, output, time, Mode.RECIPE);
        }
        Job { input = input.copy(); output = output.copy(); }
        boolean selected(ServerLevel level, ItemStack actualInput) {
            var container = new com.github.ysbbbbbb.kaleidoscopecookery.crafting.container.TeapotInput(actualInput, fluid);
            var actual = level.getRecipeManager().getRecipeFor(
                com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes.TEAPOT_RECIPE, container, level);
            if (mode == Mode.MYSTERY) return actual.isEmpty();
            return actual.filter(holder -> holder.id().equals(recipe) && holder.value().time() == time
                && ItemStack.matches(holder.value().assemble(container, level.registryAccess()), output)).isPresent();
        }
        ItemSpec ingredient() { return Goods.specOf(input); }
        ItemStack bucket() {
            ItemStack candidate = new ItemStack(BuiltInRegistries.FLUID.get(fluid).getBucket());
            var cap = candidate.getCapability(Capabilities.FluidHandler.ITEM);
            return cap != null && cap.getFluidInTank(0).getAmount() >= 1000
                && BuiltInRegistries.FLUID.getKey(cap.getFluidInTank(0).getFluid()).equals(fluid) ? candidate : ItemStack.EMPTY;
        }
    }
    private record Bench(UUID key, UUID colony, WorldPos at, Stances stances, List<Job> jobs,
                         KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof io.github.izakyl.folkways.core.api.work.Produce produce)) { return List.of(); }
            List<Refinement.Change> options = new ArrayList<>();
            for (Job job : jobs) {
                if (!Goods.matches(job.output(), produce.wanted())
                        || job.mode() != Mode.RECIPE && produce.wanted().item().isEmpty()) { continue; }
                TeapotWork work = new TeapotWork(colony, at, stances, job, bookings, changed, null);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(options);
        }
    }
}
