package io.github.izakyl.folkways.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModRecipes;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
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
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class PotStation implements KitchenStation {
    // Only successfully planned autonomous recovery roots are kept here, never ordinary recipe descendants.
    private record RecoveryKey(UUID colony, WorldPos at) { }
    private static final java.util.Map<RecoveryKey, Grown> RECOVERING = new java.util.concurrent.ConcurrentHashMap<>();
    static void rememberRecovery(UUID colony, WorldPos at, PotWork work) { RECOVERING.put(new RecoveryKey(colony, at), work.recoveryGoal()); }
    static void endRecovery(UUID colony, WorldPos at, PotWork work) { RECOVERING.remove(new RecoveryKey(colony, at), work.recoveryGoal()); }
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

    public boolean matches(BlockState state) { return state.is(ModBlocks.POT.get()); }

    static Optional<PotBlockEntity> pot(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof PotBlockEntity pot
            ? Optional.of(pot) : Optional.empty();
    }

    static boolean empty(PotBlockEntity pot) {
        return pot.getStatus() == IPot.PUT_INGREDIENT && pot.isEmpty() && pot.getResult().isEmpty()
            && !pot.getBlockState().getValue(PotBlock.HAS_OIL);
    }

    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at,
                                     Stances stances, KitchenBookings bookings, Runnable changed) {
        Optional<PotBlockEntity> found = pot(level, at);
        if (found.isEmpty()) return Optional.empty();
        PotBlockEntity pot = found.get();
        if (PotBatch.claimed(pot) && empty(pot)) {
            CompoundOwner owner = owner(level, at, pot);
            if (!owner.held() || owner.colony().filter(colony::equals).isPresent()) {
                PotBatch.clear(pot);
                changed.run();
            }
        }
        List<PotPlan> recipes = new ArrayList<>();
        if (!PotBatch.claimed(pot) && empty(pot)) {
            level.getRecipeManager().getAllRecipesFor(ModRecipes.POT_RECIPE)
                .forEach(recipe -> PotPlan.of(level, recipe).ifPresent(recipes::add));
            level.getRecipeManager().getAllRecipesFor(ModRecipes.FLEX_POT_RECIPE)
                .forEach(recipe -> PotPlan.of(level, recipe).ifPresent(recipes::add));
        }
        if (!PotBatch.claimed(pot) && empty(pot)) {
            for (PotPlan.Mode mode : List.of(PotPlan.Mode.SUSPICIOUS, PotPlan.Mode.DARK))
                PotPlan.find(level, PotPlan.proceduralId(mode)).ifPresent(recipes::add);
        }
        return Optional.of(new Bench(key, colony, at, stances, List.copyOf(recipes), bookings, changed));
    }

    private record CompoundOwner(Optional<UUID> colony, boolean held) { }
    private static CompoundOwner owner(ServerLevel level, WorldPos at, PotBlockEntity pot) {
        var marker = pot.getPersistentData().getCompound(PotBatch.KEY);
        Optional<UUID> owner = marker.hasUUID("colony") ? Optional.of(marker.getUUID("colony")) : Optional.empty();
        boolean held = owner.flatMap(id -> Colonies.of(level.getServer(), id)).flatMap(colony -> colony.holdingAt(at))
            .filter(holding -> holding.owner().equals(CookeryContent.STATION)).isPresent();
        return new CompoundOwner(owner, held);
    }

    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances,
                                KitchenBookings bookings, Runnable changed) {
        var active = activeRecovery(colony, level, at, bookings);
        if (!active.isEmpty()) { return active; }
        Optional<PotBlockEntity> found = pot(level, at).filter(pot -> PotBatch.ours(pot, colony));
        if (found.isEmpty()) return List.of();
        PotBlockEntity pot = found.get();
        Optional<PotBatch> batch = PotBatch.load(pot, level, colony).filter(current -> current.valid(pot, level));
        if (batch.isEmpty()) {
            // Changed player contents and native fully burnt batches remain physical, without a stuck work claim.
            PotBatch.clear(pot);
            changed.run();
            return List.of();
        }
        return bookings.free(at) ? List.of(PotWork.recover(at, stances, batch.get(), pot, level, bookings, changed).recoveryGoal()) : List.of();
    }

    public void release(UUID colony, ServerLevel level, WorldPos at) {
        RECOVERING.remove(new RecoveryKey(colony, at));
        pot(level, at).filter(pot -> PotBatch.ours(pot, colony)).ifPresent(PotBatch::clear);
    }

    public Component status(ServerLevel level, WorldPos at) {
        return Component.translatable(pot(level, at).map(pot -> switch (pot.getStatus()) {
            case IPot.COOKING -> "folkways.page.cookery.pot_cooking";
            case IPot.FINISHED -> "folkways.page.cookery.pot_finished";
            case IPot.BURNT -> "folkways.page.cookery.pot_burnt";
            default -> "folkways.page.cookery.idle";
        }).orElse("folkways.page.cookery.idle"));
    }

    private record Bench(UUID key, UUID colony, WorldPos at, Stances stances, List<PotPlan> recipes,
                         KitchenBookings bookings, Runnable changed) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce)) return List.of();
            List<Refinement.Change> options = new ArrayList<>();
            for (PotPlan plan : recipes) {
                if (!Goods.overlap(produce.wanted(), plan.produces())
                        || plan.mode() != PotPlan.Mode.RECIPE && produce.wanted().item().isEmpty()) continue;
                int runs = (int) produce.runs(site(), plan.perRun(), plan.count(), Optional.of(WaresContent.trade()), Integer.MAX_VALUE);
                PotWork work = new PotWork(colony, at, stances, plan, runs, bookings, changed);
                options.add(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id())));
            }
            return List.copyOf(options);
        }
    }
}
