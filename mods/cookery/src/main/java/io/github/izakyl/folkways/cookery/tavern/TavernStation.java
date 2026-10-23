package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.BarrelBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.PressingTubBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.block.brew.TapBlock;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.BarrelBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.blockentity.brew.PressingTubBlockEntity;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModRecipes;
import io.github.izakyl.folkways.core.api.colony.Closing;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.*;
import io.github.izakyl.folkways.cookery.*;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** First native Tavern production chain: grapes -> juice buckets -> bottled wine. */
public class TavernStation implements KitchenStation {
    public static final class Pressing extends TavernStation { public Pressing() { super(false); } }
    public static final class Barrel extends TavernStation { public Barrel() { super(true); } }
    final boolean barrel;
    private record Key(UUID colony, WorldPos at) { }
    private static final Map<Key, Grown> RECOVERIES = new HashMap<>();
    public TavernStation(boolean barrel) { this.barrel = barrel; }
    static ResourceLocation recipe(boolean barrel) { return ResourceLocation.fromNamespaceAndPath("kaleidoscope_tavern", barrel ? "barrel/wine" : "pressing_tub/grape_bucket"); }
    static ItemStack output(boolean barrel) { return new ItemStack(barrel ? ModItems.WINE.get() : ModItems.GRAPE_BUCKET.get()); }
    public boolean matches(BlockState state) { return state.is(barrel ? ModBlocks.BARREL.get() : ModBlocks.PRESSING_TUB.get()); }
    public BlockPos anchor(Level level, BlockPos cell) {
        return barrel ? BarrelBlock.getOriginPos(cell, level.getBlockState(cell)) : cell;
    }
    static Optional<BarrelBlockEntity> barrel(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof BarrelBlockEntity be ? Optional.of(be) : Optional.empty();
    }
    static Optional<PressingTubBlockEntity> tub(ServerLevel level, WorldPos at) {
        return at.in(level) && level.isLoaded(at.block(level)) && level.getBlockEntity(at.block(level)) instanceof PressingTubBlockEntity be ? Optional.of(be) : Optional.empty();
    }
    static Optional<BlockPos> tap(ServerLevel level, WorldPos at) {
        BlockPos center = at.block(level);
        return Direction.Plane.HORIZONTAL.stream().map(d -> center.relative(d, 2).above())
            .filter(p -> {
                BlockState state = level.getBlockState(p);
                if (!state.is(ModBlocks.TAP.get()) || state.getValue(TapBlock.OPEN)) return false;
                Direction facing = state.getValue(TapBlock.FACING);
                if (!p.equals(center.relative(facing, 2).above())) return false;
                BlockPos source = p.relative(facing.getOpposite());
                BlockState nativeBarrel = level.getBlockState(source);
                return nativeBarrel.is(ModBlocks.BARREL.get()) && nativeBarrel.getValue(BarrelBlock.FACING) == facing
                    && BarrelBlock.getOriginPos(source, nativeBarrel).equals(center);
            })
            .filter(p -> (level.getBlockState(p.below()).isAir()
                || level.getBlockState(p.below()).getBlock().asItem() == ModItems.EMPTY_BOTTLE.get()
                || level.getBlockState(p.below()).getBlock().asItem() == ModItems.WINE.get())
                && level.getBlockState(p.below(2)).isFaceSturdy(level, p.below(2), Direction.UP)).findFirst();
    }
    public Optional<Stances> stances(ServerLevel level, WorldPos at) {
        if (!barrel) {
            // The pressing worker walks into the shallow tub and jumps. The native fallOn callback
            // alone turns grapes into juice; no fabricated fall distance is passed to press().
            return tub(level, at).filter(t -> !t.getBlockState().getValue(PressingTubBlock.TILT))
                .filter(t -> level.getBlockState(at.block(level).above()).isAir() && level.getBlockState(at.block(level).above(2)).isAir())
                .map(t -> Stances.at(at));
        }
        return lid(level, at).isPresent() ? spout(level, at) : Optional.empty();
    }
    /** Juice goes in through the lid, so the loader stands on the barrel's top layer. */
    static Optional<Stances> lid(ServerLevel level, WorldPos at) {
        BlockPos top = at.block(level).above(3);
        return Stances.of(level, BlockPos.betweenClosedStream(top.offset(-1, 0, -1), top.offset(1, 0, 1)).map(BlockPos::immutable)
            .filter(feet -> Footing.withFeetAt(level, feet)).toList());
    }
    /** Bottles are set, filled and taken right at the tap: in front of the bottle under it, or beside it. */
    static Optional<Stances> spout(ServerLevel level, WorldPos at) {
        return tap(level, at).flatMap(tap -> {
            Direction out = level.getBlockState(tap).getValue(TapBlock.FACING);
            return Stances.of(level, List.of(out, out.getClockWise(), out.getCounterClockWise()).stream().map(side -> tap.below().relative(side))
                .filter(feet -> Footing.withFeetAt(level, feet) && Reach.withinReach(feet, tap) && Reach.inSight(level, feet, tap)).toList());
        });
    }
    static boolean empty(ServerLevel level, WorldPos at, boolean barrel) {
        return barrel ? tap(level, at).filter(t -> level.getBlockState(t.below()).isAir()).isPresent()
            && barrel(level, at).filter(b -> !b.isBrewing() && b.getFluid().isEmpty() && b.getOutput().getStackInSlot(0).isEmpty()
            && java.util.stream.IntStream.range(0, b.getIngredient().getSlots()).allMatch(i -> b.getIngredient().getStackInSlot(i).isEmpty())).isPresent()
            : tub(level, at).filter(t -> t.getFluidAmount() == 0 && t.getItems().getStackInSlot(0).isEmpty()).isPresent();
    }
    static boolean recipeValid(ServerLevel level, boolean barrel) {
        if (FolkwaysVanillaConfig.refusedRecipes().contains(recipe(barrel))) return false;
        if (barrel) return level.getRecipeManager().getAllRecipesFor(ModRecipes.BARREL_RECIPE).stream()
            .filter(h -> h.id().equals(recipe(true))).map(h -> h.value()).anyMatch(r ->
                r.ingredients().stream().allMatch(net.minecraft.world.item.crafting.Ingredient::isEmpty)
                && r.fluid() == com.github.ysbbbbbb.kaleidoscopetavern.init.ModFluids.GRAPE_JUICE.get()
                && r.carrier().test(new ItemStack(ModItems.EMPTY_BOTTLE.get())) && r.result().is(ModItems.WINE.get()) && r.result().getCount() == 1);
        return level.getRecipeManager().getAllRecipesFor(ModRecipes.PRESSING_TUB_RECIPE).stream()
            .filter(h -> h.id().equals(recipe(false))).map(h -> h.value()).anyMatch(r -> r.getIngredient().test(new ItemStack(ModItems.GRAPE.get()))
                && r.getFluidAmount() == 125 && r.getFluid() == com.github.ysbbbbbb.kaleidoscopetavern.init.ModFluids.GRAPE_JUICE.get());
    }
    public Optional<Workshop> freeze(UUID key, UUID colony, ServerLevel level, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed) {
        if (!empty(level, at, barrel) || !recipeValid(level, barrel)) return Optional.empty();
        return Optional.of(new Bench(key, colony, at, stances, bookings, changed, barrel));
    }
    private record Bench(UUID key, UUID colony, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed, boolean barrel) implements Workshop {
        public ResourceLocation id() { return CookeryContent.ID; }
        public WorkSite site() { return new WorkSite.AtBlock(at); }
        public List<Refinement.Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce produce) || !Goods.matches(output(barrel), produce.wanted())) return List.of();
            var work = new TavernWork(colony, at, stances, bookings, changed, barrel, null);
            return List.of(Refinement.Change.of(Grown.of(work)).holding(bookings.hold(at, work.id(), work.spec().estimate())));
        }
    }
    static void keep(UUID colony, WorldPos at, Grown work) { RECOVERIES.put(new Key(colony, at), work); }
    static void forget(UUID colony, WorldPos at, TavernWork work) { RECOVERIES.computeIfPresent(new Key(colony, at), (k, g) -> g.nodes().getFirst() == work ? null : g); }
    public List<Grown> activeRecovery(UUID colony, ServerLevel level, WorldPos at, KitchenBookings bookings) {
        Grown goal = RECOVERIES.get(new Key(colony, at));
        if (goal != null && bookings.first(at, goal.nodes().getFirst().id())) return List.of(goal);
        RECOVERIES.remove(new Key(colony, at)); return List.of();
    }
    public List<Grown> recovery(UUID colony, ServerLevel level, WorldPos at, Stances stances, KitchenBookings bookings, Runnable changed) {
        var active = activeRecovery(colony, level, at, bookings);
        if (!active.isEmpty() || !bookings.free(at)) return active;
        return TavernBatch.read(level, at, barrel).filter(b -> b.colony().equals(colony))
            .map(b -> List.of(Grown.of(new TavernWork(colony, at, stances, bookings, changed, barrel, b)))).orElse(List.of());
    }
    public void release(UUID colony, ServerLevel level, WorldPos at) {
        RECOVERIES.remove(new Key(colony, at));
        TavernBatch.read(level, at, barrel).filter(b -> b.colony().equals(colony)).ifPresent(b -> TavernBatch.clear(level, at));
    }
    public void stop(UUID colony, Closing why) { RECOVERIES.keySet().removeIf(k -> k.colony().equals(colony)); }
    public Component status(ServerLevel level, WorldPos at) {
        return Component.translatable("folkways.page.cookery." + (barrel && barrel(level, at).filter(b -> b.isBrewing()).isPresent() ? "cooking" : "idle"));
    }
}
