package io.github.izakyl.folkways.plugins.cookery.pot;

import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
import io.github.izakyl.folkways.core.api.work.Need;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

record PotBatch(UUID colony, UUID id, PotPlan plan, CompoundTag inputs, ItemStack result) {
    static final String KEY = "folkways.cookery.pot";
    PotBatch {
        inputs = inputs.copy();
        result = result.copy();
    }

    static PotBatch start(UUID colony, UUID id, PotPlan plan, PotBlockEntity pot, ServerLevel level) {
        return new PotBatch(colony, id, plan, inputs(pot, level), plan.result(level, pot.getInputs()));
    }

    void save(PotBlockEntity pot, ServerLevel level) {
        CompoundTag saved = new CompoundTag();
        saved.putUUID("colony", colony);
        saved.putUUID("batch", id);
        saved.putString("recipe", plan.id().toString());
        saved.putBoolean("flexible", plan.flexible());
        saved.put("definition", plan.definition().copy());
        saved.put("inputs", inputs.copy());
        saved.put("result", result.save(level.registryAccess()));
        pot.getPersistentData().put(KEY, saved);
        pot.setChanged();
    }

    static boolean claimed(PotBlockEntity pot) { return pot.getPersistentData().contains(KEY, Tag.TAG_COMPOUND); }
    static boolean ours(PotBlockEntity pot, UUID colony) {
        CompoundTag saved = pot.getPersistentData().getCompound(KEY);
        return saved.hasUUID("colony") && colony.equals(saved.getUUID("colony"));
    }

    static Optional<PotBatch> load(PotBlockEntity pot, ServerLevel level, UUID colony) {
        if (!ours(pot, colony)) return Optional.empty();
        CompoundTag saved = pot.getPersistentData().getCompound(KEY);
        ResourceLocation id = ResourceLocation.tryParse(saved.getString("recipe"));
        if (id == null || !saved.hasUUID("batch")) return Optional.empty();
        return PotPlan.find(level, id).filter(plan -> plan.flexible() == saved.getBoolean("flexible")
            && plan.definition().equals(saved.get("definition"))).map(plan -> new PotBatch(colony, saved.getUUID("batch"),
                plan, saved.getCompound("inputs"), ItemStack.parseOptional(level.registryAccess(), saved.getCompound("result"))));
    }

    boolean valid(PotBlockEntity pot, ServerLevel level) {
        CompoundTag saved = pot.getPersistentData().getCompound(KEY);
        if (!ours(pot, colony) || !saved.hasUUID("batch") || !saved.getUUID("batch").equals(id)
                || !pot.getBlockState().getValue(PotBlock.HAS_OIL) || !inputs.equals(inputs(pot, level))) return false;
        if (pot.getStatus() == IPot.PUT_INGREDIENT) return pot.getResult().isEmpty();
        if (pot.getStatus() != IPot.COOKING && pot.getStatus() != IPot.FINISHED && pot.getStatus() != IPot.BURNT) return false;
        ItemStack expected = result;
        if (pot.getStatus() >= IPot.FINISHED && stirs(pot, level) > 0) {
            expected = FoodBiteRegistry.getItem(FoodBiteRegistry.SUSPICIOUS_STIR_FRY).getDefaultInstance();
        }
        return ItemStack.matches(expected, pot.getResult());
    }

    static int stirs(PotBlockEntity pot, ServerLevel level) { return pot.saveCustomOnly(level.registryAccess()).getInt("StirFryCount"); }

    ItemStack product(PotBlockEntity pot) {
        return pot.getStatus() == IPot.BURNT
            ? FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance() : pot.getResult().copy();
    }

    Ingredient carrier(PotBlockEntity pot, ServerLevel level) {
        if (pot.getStatus() < IPot.FINISHED) return plan.recipe().carrier();
        Tag tag = pot.saveCustomOnly(level.registryAccess()).get("Carrier");
        return tag == null ? Ingredient.EMPTY : Ingredient.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
    }

    List<Need> servingNeeds(PotBlockEntity pot, ServerLevel level) {
        Ingredient carrier = carrier(pot, level);
        int count = pot.getStatus() < IPot.FINISHED ? plan.count() : product(pot).getCount();
        return carrier.isEmpty() ? List.of() : PotPlan.goods(carrier).map(goods -> List.of(new Need(goods, count, true))).orElseGet(List::of);
    }

    static void clear(PotBlockEntity pot) { pot.getPersistentData().remove(KEY); pot.setChanged(); }

    private static CompoundTag inputs(PotBlockEntity pot, ServerLevel level) {
        return ContainerHelper.saveAllItems(new CompoundTag(), pot.getInputs(), level.registryAccess());
    }
}
