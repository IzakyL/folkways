package io.github.izakyl.folkways.plugins.cookery.stockpot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

// The pot owns its in-flight batch, rather than the resident or a plan which can disappear on a reload.
record StockpotBatch(UUID colony, UUID id, StockpotPlan plan, CompoundTag inputs,
                     ItemStack lid, boolean replaceLid) {

    static final String KEY = "folkways.cookery.stockpot";

    StockpotBatch {
        inputs = inputs.copy();
        lid = lid.copy();
    }

    static StockpotBatch start(UUID colony, UUID id, StockpotPlan plan, StockpotBlockEntity pot,
                               ServerLevel level, boolean replaceLid) {
        return new StockpotBatch(colony, id, plan, inputs(pot, level), pot.getLidItem(), replaceLid);
    }

    void save(StockpotBlockEntity pot, ServerLevel level) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("colony", colony);
        tag.putUUID("batch", id);
        tag.putString("recipe", plan.id().toString());
        tag.put("definition", plan.definition().copy());
        tag.put("inputs", inputs.copy());
        tag.put("lid", lid.saveOptional(level.registryAccess()));
        tag.putBoolean("replace_lid", replaceLid);
        pot.getPersistentData().put(KEY, tag);
        pot.setChanged();
    }

    static boolean ours(StockpotBlockEntity pot, UUID colony) {
        CompoundTag tag = pot.getPersistentData().getCompound(KEY);
        return tag.hasUUID("colony") && colony.equals(tag.getUUID("colony"));
    }

    static boolean claimed(StockpotBlockEntity pot) {
        return pot.getPersistentData().contains(KEY, Tag.TAG_COMPOUND);
    }

    static Optional<StockpotBatch> load(StockpotBlockEntity pot, ServerLevel level, UUID colony) {
        if (!ours(pot, colony)) {
            return Optional.empty();
        }
        CompoundTag tag = pot.getPersistentData().getCompound(KEY);
        ResourceLocation recipe = ResourceLocation.tryParse(tag.getString("recipe"));
        if (recipe == null || !tag.hasUUID("batch")) {
            return Optional.empty();
        }
        var holder = StockpotPlan.find(level, recipe).orElse(null);
        if (holder == null) {
            return Optional.empty();
        }
        return StockpotPlan.of(level, holder).filter(plan -> plan.definition().equals(tag.get("definition")))
            .map(plan -> new StockpotBatch(colony, tag.getUUID("batch"), plan, tag.getCompound("inputs"),
                ItemStack.parseOptional(level.registryAccess(), tag.getCompound("lid")),
                tag.getBoolean("replace_lid")));
    }

    boolean valid(StockpotBlockEntity pot, ServerLevel level) {
        if (!ours(pot, colony) || !pot.getPersistentData().getCompound(KEY).hasUUID("batch")
                || !id.equals(pot.getPersistentData().getCompound(KEY).getUUID("batch"))
                || !plan.recipe().soupBase().equals(pot.getSoupBaseId()) || !pot.hasLid()
                || !ItemStack.isSameItemSameComponents(lid, pot.getLidItem())) {
            return false;
        }
        if (pot.getStatus() == IStockpot.PUT_INGREDIENT) {
            return inputs.equals(inputs(pot, level));
        }
        if (pot.getStatus() != IStockpot.COOKING && pot.getStatus() != IStockpot.FINISHED) {
            return false;
        }
        if (pot.getStatus() == IStockpot.COOKING && !inputs.equals(inputs(pot, level))) {
            return false;
        }
        CompoundTag current = pot.saveCustomOnly(level.registryAccess());
        return plan.id().toString().equals(current.getString("RecipeId"))
            && ItemStack.isSameItemSameComponents(plan.result(), pot.getResult())
            && pot.getTakeoutCount() == plan.portions();
    }

    static void clear(StockpotBlockEntity pot) {
        pot.getPersistentData().remove(KEY);
        pot.setChanged();
    }

    private static CompoundTag inputs(StockpotBlockEntity pot, ServerLevel level) {
        return ContainerHelper.saveAllItems(new CompoundTag(), pot.getInputs(), level.registryAccess());
    }
}
