package io.github.izakyl.folkways.cookery.tavern;

import com.github.ysbbbbbb.kaleidoscopetavern.init.ModFluids;
import com.github.ysbbbbbb.kaleidoscopetavern.init.ModItems;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** Ownership survives a cancelled task and a save; changed or player-owned batches are never adopted. */
final class TavernBatch {
    private static final String KEY = "folkways.cookery.tavern";
    record Batch(UUID colony, UUID id, boolean barrel) { }
    private TavernBatch() { }
    static void remember(ServerLevel level, WorldPos at, Batch batch) {
        var be = level.getBlockEntity(at.block(level));
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", batch.colony()); tag.putUUID("batch", batch.id()); tag.putBoolean("barrel", batch.barrel());
        be.getPersistentData().put(KEY, tag); be.setChanged();
    }
    static Optional<Batch> read(ServerLevel level, WorldPos at, boolean barrel) {
        if (!at.in(level) || !level.isLoaded(at.block(level))) return Optional.empty();
        var be = level.getBlockEntity(at.block(level));
        if (be == null) return Optional.empty();
        var tag = be.getPersistentData().getCompound(KEY);
        if (!tag.hasUUID("colony") || !tag.hasUUID("batch") || tag.getBoolean("barrel") != barrel) return Optional.empty();
        boolean valid;
        if (barrel) {
            valid = TavernStation.barrel(level, at).filter(b -> !b.isOpen()
                && java.util.stream.IntStream.range(0, b.getIngredient().getSlots()).allMatch(i -> b.getIngredient().getStackInSlot(i).isEmpty())
                && (b.isBrewing() ? b.getFluid().isEmpty() && TavernStation.recipe(true).equals(b.getRecipeId())
                    && b.getOutput().getStackInSlot(0).is(ModItems.WINE.get()) && b.getOutput().getStackInSlot(0).getCount() == 16
                    : b.getFluid().getFluid().getFluid() == ModFluids.GRAPE_JUICE.get() && b.getFluid().getFluidAmount() == 4000
                        && b.getOutput().getStackInSlot(0).isEmpty())).isPresent();
        } else {
            valid = TavernStation.tub(level, at).filter(t -> {
                var stack = t.getItems().getStackInSlot(0);
                int count = stack.isEmpty() ? 0 : stack.getCount();
                return (stack.isEmpty() || stack.is(ModItems.GRAPE.get())) && count <= 8
                    && t.getFluidAmount() == (8 - count) * 125
                    && (t.getFluidAmount() == 0 || t.getFluid().getFluid().getFluid() == ModFluids.GRAPE_JUICE.get());
            }).isPresent();
        }
        return valid ? Optional.of(new Batch(tag.getUUID("colony"), tag.getUUID("batch"), barrel)) : Optional.empty();
    }
    static void clear(ServerLevel level, WorldPos at) {
        var be = level.getBlockEntity(at.block(level));
        if (be != null) { be.getPersistentData().remove(KEY); be.setChanged(); }
    }
}
